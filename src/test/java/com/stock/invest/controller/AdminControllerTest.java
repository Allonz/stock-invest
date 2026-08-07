package com.stock.invest.controller;

import com.stock.invest.service.DataFillProgressService;
import com.stock.invest.service.DataGapFillerService;
import com.stock.invest.service.RetryProgressService;
import com.stock.invest.service.ScreeningProgressService;
import com.stock.invest.service.ScreeningService;
import com.stock.invest.service.StockDataSourcePriorityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P1-7/P1-8：AdminController 管理接口参数生效 + scanExecutor AbortPolicy + 409 并发拒绝。
 */
@WebMvcTest(AdminController.class)
@DisplayName("AdminController — 管理接口")
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScreeningService screeningService;

    @MockitoBean
    private DataGapFillerService dataGapFillerService;

    @MockitoBean
    private DataFillProgressService dataFillProgressService;

    @MockitoBean
    private StockDataSourcePriorityService stockDataSourcePriorityService;

    @MockitoBean
    private ScreeningProgressService screeningProgressService;

    @MockitoBean
    private RetryProgressService retryProgressService;

    /** 与 AsyncConfig#scanAsyncExecutor 同名的 mock —— 测试中直接控制拒绝/提交。 */
    @MockitoBean(name = "scanExecutor")
    private Executor scanExecutor;

    // ---- P1-7: windowDays/limit 透传 ----

    @Test
    @DisplayName("P1-7: trigger-screening 透传 date/windowDays/limit 给 runScreening")
    void triggerScreening_passesParams() throws Exception {
        mockMvc.perform(post("/api/admin/trigger-screening")
                        .param("date", "2026-05-18")
                        .param("limit", "3")
                        .param("windowDays", "5")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(screeningService).runScreening(LocalDate.of(2026, 5, 18), 5, 3);
    }

    @Test
    @DisplayName("P1-7: windowDays=1 原样透传（回退全窗口是 Service 侧约定），limit 缺省为 null")
    void triggerScreening_invalidWindowPassedThrough() throws Exception {
        mockMvc.perform(post("/api/admin/trigger-screening")
                        .param("date", "2026-05-18")
                        .param("windowDays", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(screeningService).runScreening(LocalDate.of(2026, 5, 18), 1, null);
    }

    @Test
    @DisplayName("R2 P1-3: 无参调用 → limit/windowDays 均为 null（全窗口全量，与 async 语义一致）")
    void triggerScreening_noParamsDefaultsToNull() throws Exception {
        mockMvc.perform(post("/api/admin/trigger-screening")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(screeningService).runScreening(any(LocalDate.class), isNull(), isNull());
    }

    // ---- P1-8: 409 并发拒绝 ----

    @Test
    @DisplayName("P1-8: 补缺已在运行时 trigger-data-fill 返回 409")
    void triggerDataFill_busyReturns409() throws Exception {
        when(dataGapFillerService.isRunning()).thenReturn(true);

        mockMvc.perform(post("/api/admin/trigger-data-fill")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已在运行")));

        verify(scanExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("P1-8: 补缺/重试在运行时 trigger-retry-tasks 返回 409")
    void triggerRetryTasks_busyReturns409() throws Exception {
        when(dataGapFillerService.isRunning()).thenReturn(true);

        mockMvc.perform(post("/api/admin/trigger-retry-tasks")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ---- P1-8: AbortPolicy 拒绝 ----

    @Test
    @DisplayName("R2 P2-2: scanExecutor 拒绝（AbortPolicy）→ 503 + QUEUE_FULL + 进度条目已清理")
    void triggerScreeningAsync_rejectedTaskSurfacesError() throws Exception {
        when(screeningProgressService.startScreening(anyList(), anyInt())).thenReturn("task123");
        when(screeningProgressService.getProgress("task123"))
                .thenReturn(new ScreeningProgressService.ScreeningProgress());
        doThrow(new TaskRejectedException("queue full"))
                .when(scanExecutor).execute(any(Runnable.class));

        mockMvc.perform(post("/api/admin/trigger-screening-async")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorType").value("QUEUE_FULL"));

        // 拒绝路径清理已创建的进度条目，防幽灵条目残留
        verify(screeningProgressService).removeProgress("task123");
    }

    @Test
    @DisplayName("R2 P2-2: trigger-data-fill 队列满 → 503 + QUEUE_FULL + 进度条目已清理")
    void triggerDataFill_rejectedReturns503AndCleansProgress() throws Exception {
        when(dataFillProgressService.startFill()).thenReturn("fill123");
        when(dataFillProgressService.getProgress("fill123"))
                .thenReturn(new DataFillProgressService.FillProgress());
        doThrow(new TaskRejectedException("queue full"))
                .when(scanExecutor).execute(any(Runnable.class));

        mockMvc.perform(post("/api/admin/trigger-data-fill")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorType").value("QUEUE_FULL"));

        verify(dataFillProgressService).removeProgress("fill123");
    }

    // ---- 回归：正常提交 ----

    @Test
    @DisplayName("trigger-data-fill 空闲时提交到 scanExecutor 并返回 taskId")
    void triggerDataFill_successSubmitsToExecutor() throws Exception {
        when(dataGapFillerService.isRunning()).thenReturn(false);
        when(dataFillProgressService.startFill()).thenReturn("fill-001");
        DataFillProgressService.FillProgress progress = new DataFillProgressService.FillProgress();
        when(dataFillProgressService.getProgress("fill-001")).thenReturn(progress);

        mockMvc.perform(post("/api/admin/trigger-data-fill")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("fill-001"));

        verify(scanExecutor, times(1)).execute(any(Runnable.class));
    }
}

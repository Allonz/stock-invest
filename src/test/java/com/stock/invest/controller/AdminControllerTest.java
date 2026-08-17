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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P1-7/P1-8：AdminController 管理接口参数生效 + scanExecutor AbortPolicy + 409 并发拒绝。
 */
@WebMvcTest(value = AdminController.class, properties = "admin.api-key=test-admin-key")
@DisplayName("AdminController — 管理接口")
class AdminControllerTest {

    private static final String ADMIN_API_KEY = "test-admin-key";

    @Autowired
    private MockMvc mockMvc;

    private static MockHttpServletRequestBuilder adminPost(String urlTemplate, Object... uriVars) {
        return post(urlTemplate, uriVars).header("X-Admin-API-Key", ADMIN_API_KEY);
    }

    @Test
    @DisplayName("缺少 X-Admin-API-Key 返回 401")
    void adminEndpoint_missingKey_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/trigger-screening-async"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("错误 X-Admin-API-Key 返回 401")
    void adminEndpoint_wrongKey_returns401() throws Exception {
        mockMvc.perform(post("/api/admin/trigger-screening-async")
                        .header("X-Admin-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

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

// ---- P1-8: 409 并发拒绝 ----

    @Test
    @DisplayName("P1-8: 补缺已在运行时 trigger-data-fill 返回 409")
    void triggerDataFill_busyReturns409() throws Exception {
        when(dataGapFillerService.isRunning()).thenReturn(true);

        mockMvc.perform(adminPost("/api/admin/trigger-data-fill")
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

        mockMvc.perform(adminPost("/api/admin/trigger-retry-tasks")
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

        mockMvc.perform(adminPost("/api/admin/trigger-screening-async")
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

        mockMvc.perform(adminPost("/api/admin/trigger-data-fill")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorType").value("QUEUE_FULL"));

        verify(dataFillProgressService).removeProgress("fill123");
    }

    @Test
    @DisplayName("R2 P2-2: run-screening-async 队列满 → 503 + QUEUE_FULL + 筛选进度条目已清理")
    void runScreeningAsync_rejectedReturns503AndCleansProgress() throws Exception {
        when(screeningProgressService.startScreening(anyList(), anyInt())).thenReturn("adv123");
        when(screeningProgressService.getProgress("adv123"))
                .thenReturn(new ScreeningProgressService.ScreeningProgress());
        doThrow(new TaskRejectedException("queue full"))
                .when(scanExecutor).execute(any(Runnable.class));

        mockMvc.perform(adminPost("/api/admin/run-screening-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorType").value("QUEUE_FULL"));

        verify(screeningProgressService).removeProgress("adv123");
    }

    @Test
    @DisplayName("R2 P2-2: trigger-retry-tasks 队列满 → 503 + QUEUE_FULL")
    void triggerRetryTasks_rejectedReturns503() throws Exception {
        when(dataGapFillerService.isRunning()).thenReturn(false);
        doThrow(new TaskRejectedException("queue full"))
                .when(scanExecutor).execute(any(Runnable.class));

        mockMvc.perform(adminPost("/api/admin/trigger-retry-tasks")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorType").value("QUEUE_FULL"));

        // 该端点无进度条目创建，无需清理
        verify(screeningProgressService, never()).removeProgress(anyString());
        verify(dataFillProgressService, never()).removeProgress(anyString());
    }

    // ---- 回归：正常提交 ----

    @Test
    @DisplayName("trigger-data-fill 空闲时提交到 scanExecutor 并返回 taskId")
    void triggerDataFill_successSubmitsToExecutor() throws Exception {
        when(dataGapFillerService.isRunning()).thenReturn(false);
        when(dataFillProgressService.startFill()).thenReturn("fill-001");
        DataFillProgressService.FillProgress progress = new DataFillProgressService.FillProgress();
        when(dataFillProgressService.getProgress("fill-001")).thenReturn(progress);

        mockMvc.perform(adminPost("/api/admin/trigger-data-fill")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("fill-001"));

        verify(scanExecutor, times(1)).execute(any(Runnable.class));
    }

    // ---- R2 P3-9: fillGaps 返回值 → 进度 stage ----

    @Test
    @DisplayName("R2 P3-9: fillGaps 返回 false（互斥被抢）→ 进度 stage=SKIPPED")
    void triggerDataFill_skippedSetsStage() throws Exception {
        when(dataGapFillerService.isRunning()).thenReturn(false);
        when(dataGapFillerService.fillGaps()).thenReturn(false);
        when(dataFillProgressService.startFill()).thenReturn("fill-skip");
        DataFillProgressService.FillProgress progress = new DataFillProgressService.FillProgress();
        when(dataFillProgressService.getProgress("fill-skip")).thenReturn(progress);

        mockMvc.perform(adminPost("/api/admin/trigger-data-fill")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("fill-skip"));

        // 执行被提交的异步体 —— 互斥拒绝应把 stage 置为 SKIPPED 而非静默成功
        org.mockito.ArgumentCaptor<Runnable> captor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scanExecutor).execute(captor.capture());
        captor.getValue().run();

        assertEquals("SKIPPED", progress.getStage(), "mutex-rejected fill must surface SKIPPED");
        assertFalse(progress.isRunning(), "running flag must clear after task body");
    }

    @Test
    @DisplayName("R2 P3-9: fillGaps 返回 true → 进度 stage=COMPLETED")
    void triggerDataFill_completedSetsStage() throws Exception {
        when(dataGapFillerService.isRunning()).thenReturn(false);
        when(dataGapFillerService.fillGaps()).thenReturn(true);
        when(dataFillProgressService.startFill()).thenReturn("fill-ok");
        DataFillProgressService.FillProgress progress = new DataFillProgressService.FillProgress();
        when(dataFillProgressService.getProgress("fill-ok")).thenReturn(progress);

        mockMvc.perform(adminPost("/api/admin/trigger-data-fill")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Runnable> captor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scanExecutor).execute(captor.capture());
        captor.getValue().run();

        assertEquals("COMPLETED", progress.getStage());
        assertFalse(progress.isRunning(), "running flag must clear after task body");
    }

    // ---- run-screening-async 参数透传与解析 ----

    @Test
    @DisplayName("run-screening-async 透传 windowDays/limit 给 runScreening")
    void runScreeningAsync_passesWindowAndLimit() throws Exception {
        when(screeningProgressService.startScreening(anyList(), anyInt())).thenReturn("adv-param");
        ScreeningProgressService.ScreeningProgress progress = new ScreeningProgressService.ScreeningProgress();
        ScreeningProgressService.WindowProgress window = new ScreeningProgressService.WindowProgress();
        window.setDays(2);
        window.setStatus("RUNNING");
        progress.setWindows(java.util.List.of(window));
        when(screeningProgressService.getProgress("adv-param"))
                .thenReturn(progress);

        mockMvc.perform(adminPost("/api/admin/run-screening-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":3,\"windowDays\":2}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Runnable> captor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scanExecutor).execute(captor.capture());
        captor.getValue().run();

        verify(screeningService).runScreening(any(LocalDate.class), eq(2), eq(3));
        verify(screeningService, never()).runScreening(any(LocalDate.class));
    }

    @Test
    @DisplayName("run-screening-async 非法 limit 返回 400")
    void runScreeningAsync_invalidLimitReturns400() throws Exception {
        mockMvc.perform(adminPost("/api/admin/run-screening-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":\"abc\",\"windowDays\":2}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(scanExecutor, never()).execute(any());
        verify(screeningService, never()).runScreening(any(LocalDate.class), any(), any());
    }

    @Test
    @DisplayName("run-screening-async limit=0 返回 400")
    void runScreeningAsync_zeroLimitReturns400() throws Exception {
        mockMvc.perform(adminPost("/api/admin/run-screening-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":0,\"windowDays\":2}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(scanExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("run-screening-async 负数 windowDays 返回 400")
    void runScreeningAsync_negativeWindowDaysReturns400() throws Exception {
        mockMvc.perform(adminPost("/api/admin/run-screening-async")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":3,\"windowDays\":-2}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(scanExecutor, never()).execute(any());
    }

}

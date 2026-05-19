package com.stock.invest.controller;

import com.stock.invest.entity.DataFillTask;
import com.stock.invest.repository.DataFillTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class FillTasksPageController {

    private static final Logger log = LoggerFactory.getLogger(FillTasksPageController.class);

    private final DataFillTaskRepository dataFillTaskRepository;

    public FillTasksPageController(DataFillTaskRepository dataFillTaskRepository) {
        this.dataFillTaskRepository = dataFillTaskRepository;
    }

    @GetMapping("/page/fill-tasks")
    public String fillTasks(
            @RequestParam(required = false) String status,
            Model model
    ) {
        List<DataFillTask> tasks;
        if (status != null && !status.isBlank()) {
            tasks = dataFillTaskRepository.findByStatusOrderByCreatedAtDesc(status);
        } else {
            tasks = dataFillTaskRepository.findAllByOrderByCreatedAtDesc();
        }

        model.addAttribute("tasks", tasks);
        model.addAttribute("selectedStatus", status);

        // 各状态计数
        model.addAttribute("countPending", dataFillTaskRepository.countByStatus("pending"));
        model.addAttribute("countInProgress", dataFillTaskRepository.countByStatus("retrying"));
        model.addAttribute("countCompleted", dataFillTaskRepository.countByStatus("completed"));
        model.addAttribute("countFailed", dataFillTaskRepository.countByStatus("stopped"));

        return "fill-tasks";
    }
}

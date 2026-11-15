package com.ratelimiter.controller;

import com.ratelimiter.metrics.RateLimitMetrics;
import com.ratelimiter.model.TopClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Thymeleaf admin UI — top 10 clients by current usage.
 */
@Controller
public class AdminController {

    private final RateLimitMetrics metrics;
    private final int defaultLimit;

    public AdminController(RateLimitMetrics metrics,
                           @Value("${ratelimiter.default-limit:100}") int defaultLimit) {
        this.metrics = metrics;
        this.defaultLimit = defaultLimit;
    }

    @GetMapping({"/", "/admin"})
    public String adminPage(Model model) {
        model.addAttribute("trackedClients", metrics.trackedClientCount());
        model.addAttribute("topClients", metrics.topClients(10, defaultLimit));
        return "admin";
    }
}

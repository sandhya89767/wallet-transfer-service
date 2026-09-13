package com.paytm.wallet.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MetricsController {
    @GetMapping("/metrics")
    public String metrics() {
        return "forward:/actuator/prometheus";
    }
}
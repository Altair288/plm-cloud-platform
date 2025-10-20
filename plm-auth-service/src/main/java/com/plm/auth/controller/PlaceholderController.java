package main.java.com.plm.auth.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlaceholderController {
    @GetMapping("/auth/ping")
    public String ping() {return "auth ok";}
}

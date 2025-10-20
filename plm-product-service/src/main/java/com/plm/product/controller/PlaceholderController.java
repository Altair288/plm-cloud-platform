package main.java.com.plm.product.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlaceholderController {
    @GetMapping("/product/ping")
    public String ping() {return "product ok";}
}

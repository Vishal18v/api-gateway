package com.example.ciao_service.ciao;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CiaoController {

    @GetMapping("/ciao")
    public String sayCiao() {
        return "Ciao from Microservice!";
    }
}
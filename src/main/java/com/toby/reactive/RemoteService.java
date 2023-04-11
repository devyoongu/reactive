package com.toby.reactive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class RemoteService {

    @Slf4j
    @RestController
    public static class MyController {
        @GetMapping("/service")
        public String rest(String req) throws InterruptedException {
            log.info("remote req is = {}", req);
            Thread.sleep(2000);
            return req + "/service";
        }

        @GetMapping("/service2")
        public String rest2(String req) throws InterruptedException {
            log.info("remote req is = {}", req);
            Thread.sleep(2000);
            return req + "/service2";
        }
    }

    public static void main(String[] args) {
        System.setProperty("server.port", "8081"); // server.port 속성 추가
        System.setProperty("server.tomcat.max-threads", "1000");
        SpringApplication.run(RemoteService.class, args);
    }

}

package com.ep.boardgame;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * D-149: boardgame-service entry point.
 * <p>
 * Board game education platform — first a full education platform (course/lesson
 * binding for Match-as-Lesson), then online play (Xiangqi rule engine, live
 * game rooms, matchmaking, replay).
 */
@SpringBootApplication
@EnableDubbo
public class BoardGameApplication {

    public static void main(String[] args) {
        SpringApplication.run(BoardGameApplication.class, args);
    }
}

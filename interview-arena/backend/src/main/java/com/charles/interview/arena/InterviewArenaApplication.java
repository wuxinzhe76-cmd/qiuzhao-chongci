package com.charles.interview.arena;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.charles.interview.arena.mapper")
public class InterviewArenaApplication {

	public static void main(String[] args) {
		SpringApplication.run(InterviewArenaApplication.class, args);
	
	}

}
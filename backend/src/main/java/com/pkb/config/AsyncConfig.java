package com.pkb.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 文档处理流水线线程池。
 */
@Configuration
public class AsyncConfig {

    @Bean("pipelineExecutor")
    public ThreadPoolTaskExecutor pipelineExecutor(PkbProperties props) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(props.getPipeline().getWorkers());
        ex.setMaxPoolSize(Math.max(props.getPipeline().getWorkers(), 4));
        ex.setQueueCapacity(props.getPipeline().getQueueCapacity());
        ex.setThreadNamePrefix("pkb-pipeline-");
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }

    @Bean("graphExecutor")
    public ThreadPoolTaskExecutor graphExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("pkb-graph-");
        ex.initialize();
        return ex;
    }

    @Bean("chatExecutor")
    public ThreadPoolTaskExecutor chatExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("pkb-chat-");
        ex.initialize();
        return ex;
    }
}

package com.pkb.knowledge;

import lombok.Data;

@Data
public class Document {
    private Long id;
    private Long kbId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String filePath;
    /** PENDING / PROCESSING / INDEXED / FAILED */
    private String status;
    private Double progress;
    private String errorMessage;
    private Integer chunkCount;
    private String createdAt;
    private String updatedAt;
}

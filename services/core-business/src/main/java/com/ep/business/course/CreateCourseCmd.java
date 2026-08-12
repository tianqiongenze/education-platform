package com.ep.business.course;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;
import java.math.BigDecimal;

public class CreateCourseCmd implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String title;
    private String description;
    @NotNull
    private Long teacherId;
    private Integer category;
    private Integer difficulty;
    private BigDecimal price;

    public CreateCourseCmd() {
    }

    public CreateCourseCmd(String title, String description, Long teacherId, Integer category,
                           Integer difficulty, BigDecimal price) {
        this.title = title;
        this.description = description;
        this.teacherId = teacherId;
        this.category = category;
        this.difficulty = difficulty;
        this.price = price;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
    public Integer getCategory() { return category; }
    public void setCategory(Integer category) { this.category = category; }
    public Integer getDifficulty() { return difficulty; }
    public void setDifficulty(Integer difficulty) { this.difficulty = difficulty; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
}

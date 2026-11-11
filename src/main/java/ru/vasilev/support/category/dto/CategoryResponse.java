package ru.vasilev.support.category.dto;

import ru.vasilev.support.category.entity.Category;

public record CategoryResponse(long id, String name) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName());
    }
}

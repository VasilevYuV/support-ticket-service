package ru.vasilev.support.category.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.vasilev.support.category.dto.CategoryResponse;
import ru.vasilev.support.category.entity.Category;
import ru.vasilev.support.category.repository.CategoryRepository;
import ru.vasilev.support.common.error.ApiException;

@Service
@Transactional(readOnly = true)
public class CategoryService {
    private final CategoryRepository repository;

    public CategoryService(CategoryRepository repository) {
        this.repository = repository;
    }

    public List<CategoryResponse> getAll() {
        return repository.findAllByOrderByIdAsc().stream().map(CategoryResponse::from).toList();
    }

    public Category getRequired(long id) {
        return repository
                .findById(id)
                .orElseThrow(() -> ApiException.validation("categoryId", "Категория не найдена."));
    }
}

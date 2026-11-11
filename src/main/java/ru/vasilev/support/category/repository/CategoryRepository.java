package ru.vasilev.support.category.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.vasilev.support.category.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findAllByOrderByIdAsc();
}

package com.pkb.knowledge;

import com.pkb.common.BusinessException;
import com.pkb.dao.CategoryDao;
import com.pkb.dao.KnowledgeBaseDao;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryService {

    private final CategoryDao dao;
    private final KnowledgeBaseDao kbDao;

    public CategoryService(CategoryDao dao, KnowledgeBaseDao kbDao) {
        this.dao = dao;
        this.kbDao = kbDao;
    }

    public List<Category> list() {
        return dao.findAll();
    }

    public Category save(Category c) {
        if (c.getName() == null || c.getName().isBlank()) {
            throw new BusinessException("请填写分类名称");
        }
        if (c.getParentId() != null && c.getParentId() != 0) {
            if (dao.findById(c.getParentId()) == null) {
                throw new BusinessException("父分类不存在");
            }
            // 限制两级：不允许再建子分类的子分类
            Category parent = dao.findById(c.getParentId());
            if (parent.getParentId() != null && parent.getParentId() != 0) {
                throw new BusinessException("分类最多支持两级");
            }
        }
        if (c.getId() == null) {
            c.setId(dao.insert(c));
        } else {
            dao.update(c);
        }
        return dao.findById(c.getId());
    }

    public void delete(long id) {
        if (dao.countChildren(id) > 0) {
            throw new BusinessException("该分类下存在子分类，请先删除子分类");
        }
        if (kbDao.countByCategory(id) > 0) {
            throw new BusinessException("该分类下存在知识库，请先移动或删除知识库");
        }
        dao.delete(id);
    }
}

package com.es.datasearch.service;

import com.es.datasearch.param.QueryArticleSearchVO;
import com.es.datasearch.result.ArticleResultByEsDO;
import org.springframework.stereotype.Service;

/**
 * @author yiheni
 */
public interface ArticleService {
    /**
     * 查询
     *
     * @param queryArticleSearchVO
     * @return
     */
    ArticleResultByEsDO getArticleList(QueryArticleSearchVO queryArticleSearchVO);

}

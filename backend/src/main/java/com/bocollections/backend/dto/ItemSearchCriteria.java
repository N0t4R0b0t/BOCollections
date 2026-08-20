package com.bocollections.backend.dto;

import com.bocollections.backend.entity.MediaCategory;

/** Catalogue filter+sort builder inputs — see ItemController.search / ItemService.search. */
public record ItemSearchCriteria(
        String q,
        MediaCategory category,
        String format,
        Integer yearFrom,
        Integer yearTo,
        String genre,
        String sort
) {
}

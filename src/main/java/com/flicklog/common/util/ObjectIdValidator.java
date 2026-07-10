package com.flicklog.common.util;

import com.flicklog.common.exception.ApiException;
import org.bson.types.ObjectId;

public final class ObjectIdValidator {

    private ObjectIdValidator() {}

    public static void requireValid(String id, String errorMessage) {
        if (!ObjectId.isValid(id)) {
            throw new ApiException(errorMessage, 400);
        }
    }
}
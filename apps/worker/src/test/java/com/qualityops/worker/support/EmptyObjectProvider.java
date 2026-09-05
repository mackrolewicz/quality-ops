package com.qualityops.worker.support;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;

/** An {@link ObjectProvider} for unit tests: {@link #instance()} never has a
 *  bean; {@link #of(Object)} always yields the given one. */
public class EmptyObjectProvider<T> implements ObjectProvider<T> {

    public static <T> EmptyObjectProvider<T> instance() {
        return new EmptyObjectProvider<>();
    }

    public static <T> EmptyObjectProvider<T> of(T value) {
        return new EmptyObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    @Override
    public T getObject() throws BeansException {
        throw new IllegalStateException("no bean available");
    }

    @Override
    public T getObject(Object... args) throws BeansException {
        throw new IllegalStateException("no bean available");
    }

    @Override
    @Nullable
    public T getIfAvailable() throws BeansException {
        return null;
    }

    @Override
    @Nullable
    public T getIfUnique() throws BeansException {
        return null;
    }
}

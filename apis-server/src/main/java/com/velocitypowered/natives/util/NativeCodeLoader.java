package com.velocitypowered.natives.util;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Drop-in override of Velocity's {@link NativeCodeLoader} supporting lazy variant selection
 * so unused native subsystems (such as cipher in offline/proxy mode) are never extracted or loaded.
 *
 * @param <T> loaded factory type
 */
public final class NativeCodeLoader<T> implements Supplier<T> {

    static final BooleanSupplier ALWAYS = () -> true;

    private final @Nullable List<Variant<T>> eagerVariants;
    private final @Nullable Supplier<List<Variant<T>>> lazyVariants;
    private volatile @Nullable Variant<T> selected;

    NativeCodeLoader(final List<Variant<T>> variants) {
        this.eagerVariants = variants;
        this.lazyVariants = null;
        this.selected = getVariant(variants);
    }

    NativeCodeLoader(final Supplier<List<Variant<T>>> lazyVariants) {
        this.eagerVariants = null;
        this.lazyVariants = lazyVariants;
    }

    @Override
    public T get() {
        return this.ensureSelected().constructed;
    }

    public String getLoadedVariant() {
        return this.ensureSelected().name;
    }

    private Variant<T> ensureSelected() {
        final Variant<T> cached = this.selected;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            final Variant<T> rechecked = this.selected;
            if (rechecked != null) {
                return rechecked;
            }
            final List<Variant<T>> variants = this.lazyVariants != null ? this.lazyVariants.get() : this.eagerVariants;
            if (variants == null) {
                throw new IllegalArgumentException("Can't find any suitable variants");
            }
            final Variant<T> resolved = getVariant(variants);
            this.selected = resolved;
            return resolved;
        }
    }

    private static <T> Variant<T> getVariant(final List<Variant<T>> variants) {
        for (final Variant<T> variant : variants) {
            final T instance = variant.get();
            if (instance != null) {
                return variant;
            }
        }
        throw new IllegalArgumentException("Can't find any suitable variants");
    }

    private enum Status {
        NOT_AVAILABLE,
        POSSIBLY_AVAILABLE,
        SETUP,
        SETUP_FAILURE
    }

    static final class Variant<T> {

        private Status status;
        private final Runnable setup;
        private final String name;
        private final Supplier<T> object;
        private @Nullable T constructed;

        Variant(final BooleanSupplier possible, final Runnable setup, final String name, final T object) {
            this(possible, setup, name, () -> object);
        }

        Variant(
            final BooleanSupplier possible,
            final Runnable setup,
            final String name,
            final Supplier<T> object
        ) {
            this.status = possible.getAsBoolean() ? Status.POSSIBLY_AVAILABLE : Status.NOT_AVAILABLE;
            this.setup = setup;
            this.name = name;
            this.object = object;
        }

        public @Nullable T get() {
            if (this.status == Status.NOT_AVAILABLE || this.status == Status.SETUP_FAILURE) {
                return null;
            }
            if (this.status == Status.POSSIBLY_AVAILABLE) {
                try {
                    this.setup.run();
                    this.constructed = this.object.get();
                    this.status = Status.SETUP;
                } catch (Exception _) {
                    this.status = Status.SETUP_FAILURE;
                    return null;
                }
            }
            return this.constructed;
        }
    }
}

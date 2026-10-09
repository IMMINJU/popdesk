package io.popdesk.deploy;

import java.util.function.Supplier;

/**
 * Something slow that several tests of a class share (a started stack, a sample dump), made by the
 * first test that asks. When making it failed, every later test fails with the same assertion, not
 * with a JUnit lifecycle error: a failure in {@code @BeforeAll} would count as an error in the red
 * check (C-6).
 */
final class Once<T> {

    private final String what;
    private final Supplier<T> make;
    private T value;
    private AssertionError failure;

    Once(String what, Supplier<T> make) {
        this.what = what;
        this.make = make;
    }

    synchronized T get() {
        if (failure != null) {
            throw new AssertionError("Earlier, " + what + " failed: " + failure.getMessage(), failure);
        }
        if (value == null) {
            try {
                value = make.get();
            } catch (AssertionError e) {
                failure = e;
                throw e;
            } catch (RuntimeException e) {
                failure = new AssertionError(what + " failed: " + e, e);
                throw failure;
            }
        }
        return value;
    }

    synchronized boolean made() {
        return value != null;
    }

    /** The value when it was made, else null. */
    synchronized T peek() {
        return value;
    }
}

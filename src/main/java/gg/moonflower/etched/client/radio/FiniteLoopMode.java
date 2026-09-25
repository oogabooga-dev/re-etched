package gg.moonflower.etched.client.radio;

/** Client-side repeat policy for an active finite program. Explicit skip bypasses repeat-one. */
public enum FiniteLoopMode {
    OFF,
    ALL,
    ONE;

    int nextIndex(int current, int count, boolean skip) {
        if (!skip && this == ONE) {
            return current;
        }
        int next = current + 1;
        return next < count ? next : this == ALL ? 0 : -1;
    }
}

package net.floodlightcontroller.ratelimiter;

public class SwitchPair {
	private final long x;
    private final long y;

    public SwitchPair(long x, long y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SwitchPair)) return false;
        SwitchPair key = (SwitchPair) o;
        return (x == key.x && y == key.y) || (x == key.y && y == key.x);
    }

    @Override
    public int hashCode() {
        int result = (int)(x^(x>>>32));
        result += (int)(y^(y>>32));
        return result;
    }
}

package net.floodlightcontroller.ratelimiter;

import net.floodlightcontroller.core.module.IFloodlightService;

public interface RateLimiterService extends IFloodlightService {

	public void addPolicy(Policy p);
	
	public void deletePolicy(Policy p);
}

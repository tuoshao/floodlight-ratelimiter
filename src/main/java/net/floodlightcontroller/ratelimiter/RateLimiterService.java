package net.floodlightcontroller.ratelimiter;

import java.util.List;

import net.floodlightcontroller.core.module.IFloodlightService;

public interface RateLimiterService extends IFloodlightService {

	public void addPolicy(Policy p);
	
	public void deletePolicy(Policy p);
	
	public boolean checkIfPolicyExists(Policy policy);

	public List<Policy> getPolicies();
}

package net.floodlightcontroller.ratelimiter;

import java.util.List;
import java.util.Set;

import org.openflow.protocol.OFMatch;

public class Rule {
	OFMatch match;
	Set<Integer> flows;
	Set<Integer> policies;
	
	public int hashCode(){
		return match.hashCode();
	}
}

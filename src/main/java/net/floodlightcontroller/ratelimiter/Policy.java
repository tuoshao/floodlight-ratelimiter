package net.floodlightcontroller.ratelimiter;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.floodlightcontroller.core.IOFSwitch;

import org.openflow.protocol.OFMatch;

public class Policy {
	int policyid;
	Set<OFMatch> rules;
	Set<Flow> flows;
	int flowcount;
	short speed;
	long dpid;
	int port;
	int avgdistance;

	public Policy(Set<OFMatch> r){
		rules = r;
		flows = new HashSet<Flow>();
		flowcount = 0;
		speed = 0;
		//dpid and port are initialized with max value, meaning it's uninstalled
		dpid = Long.MAX_VALUE;
		port = Integer.MAX_VALUE;
		avgdistance = 0;
		policyid = this.hashCode();
	}
	
	public void addFlow(Flow flow){
		flows.add(flow);
	}
	
	public int hashCode(){
		int result=1;
		Iterator it = rules.iterator();
		while(it.hasNext()){
			OFMatch rule = (OFMatch) it.next();
			result = result*rule.hashCode();
		}
		return result;
	}
}

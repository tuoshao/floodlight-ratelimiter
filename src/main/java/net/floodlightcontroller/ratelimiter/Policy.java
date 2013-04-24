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
	short port;
	int queue;
	int avgdistance;
	short priority;

	public Policy(Set<OFMatch> r, short speed){
		rules = r;
		flows = new HashSet<Flow>();
		flowcount = 0;
		this.speed = speed;
		//dpid and port are initialized with max value, meaning it's uninstalled
		dpid = Long.MAX_VALUE;
		port = Short.MAX_VALUE;
		avgdistance = 0;
		policyid = this.hashCode();
		priority = 32767;
	}
	
	public void addFlow(Flow flow){
		flows.add(flow);
	}
	
	public Set<OFMatch> getRules(){
		return this.rules;
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

	public void setQueue(int q) {
		// TODO Auto-generated method stub
		this.queue = q;
	}

	public void setPort(short p) {
		// TODO Auto-generated method stub
		this.port = p;
	}
}

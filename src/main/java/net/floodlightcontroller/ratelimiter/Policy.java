package net.floodlightcontroller.ratelimiter;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.floodlightcontroller.core.IOFSwitch;

import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.routing.Link;
import org.openflow.protocol.OFMatch;

public class Policy {
	int policyid;
	Set<OFMatch> rules;
	Set<Flow> flows;
	int flowcount;
	short speed;
    SwitchPort swport;
	int queue;
	int avgdistance;
	short priority;

	public Policy(Set<OFMatch> r, short speed){
		rules = r;
		flows = new HashSet<Flow>();
		flowcount = 0;
		this.speed = speed;
        swport = null;
		avgdistance = 0;
		policyid = this.hashCode();
		priority = 32767;
	}
	
	public void addFlow(Flow flow){
		flows.add(flow);
	}

    public Set<Flow> getFLows() {
        return flows;
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
		this.queue = q;
	}

    public SwitchPort getSwport() {
        return swport;
    }

    public void setSwport(SwitchPort swport) {
        this.swport = swport;
    }

    public long getDpid() {
        return swport.getSwitchDPID();
    }

    public short getPort() {
        return (short) swport.getPort();
    }
}

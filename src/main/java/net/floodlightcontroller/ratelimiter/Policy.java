package net.floodlightcontroller.ratelimiter;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.floodlightcontroller.devicemanager.SwitchPort;
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

	public Policy(Set<OFMatch> r, short s){
		rules = r;
        Set<Flow> flowset = new HashSet<Flow>();
		flows = Collections.synchronizedSet(flowset);
		flowcount = 0;
		speed = s;
        swport = null;
		avgdistance = 0;
		policyid = this.hashCode();
		priority = 32767;
        /* TODO debug */
        queue = 1;
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
	
	
	private int getOFMatchHashCode(OFMatch match){
		int prime = 131;
		int result = 1;
		int wildcard = match.getWildcards();
		result = result*prime + match.getNetworkTypeOfService();
		if(!((wildcard & OFMatch.OFPFW_IN_PORT) == OFMatch.OFPFW_IN_PORT)) result = result*prime + match.getInputPort();
		if(!((wildcard & OFMatch.OFPFW_DL_VLAN) == OFMatch.OFPFW_DL_VLAN)) result = result*prime + match.getDataLayerVirtualLan();
		if(!((wildcard & OFMatch.OFPFW_DL_SRC) == OFMatch.OFPFW_DL_SRC)) result = result*prime + Arrays.hashCode(match.getDataLayerSource());
		if(!((wildcard & OFMatch.OFPFW_DL_DST) == OFMatch.OFPFW_DL_DST)) result = result*prime + Arrays.hashCode(match.getDataLayerDestination());
		if(!((wildcard & OFMatch.OFPFW_DL_TYPE) == OFMatch.OFPFW_DL_TYPE)) result = result*prime + match.getDataLayerType();
		if(!((wildcard & OFMatch.OFPFW_NW_PROTO) == OFMatch.OFPFW_NW_PROTO)) result = result*prime + match.getNetworkProtocol();
		if(!((wildcard & OFMatch.OFPFW_TP_SRC) == OFMatch.OFPFW_TP_SRC)) result = result*prime + match.getTransportSource();
		if(!((wildcard & OFMatch.OFPFW_TP_DST) == OFMatch.OFPFW_TP_DST)) result = result*prime + match.getTransportDestination();
		
		int matchSrcMask = match.getNetworkSourceMaskLen();
		int subnetSrc = match.getNetworkSource() & ((matchSrcMask==0)? 0:0xffffffff << (32-matchSrcMask));
		result = result*prime + subnetSrc;
		
		int matchDstMask = match.getNetworkDestinationMaskLen();
		int subnetDst = match.getNetworkDestination() & ((matchDstMask==0)? 0:0xffffffff << (32-matchDstMask));
		result = result*prime + subnetDst;

		return result;
	}
	
	public int hashCode(){
		int prime = 2521;
		int result=1;
		for(OFMatch r:rules){
			result = result*prime + getOFMatchHashCode(r);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Policy policy = (Policy) o;

        if (rules != null ? !rules.equals(policy.rules) : policy.rules != null) return false;

        return true;
    }
}

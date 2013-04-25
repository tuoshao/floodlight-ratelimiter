package net.floodlightcontroller.ratelimiter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.NodePortTuple;

import org.openflow.protocol.OFMatch;
import org.openflow.util.HexString;

import com.sun.xml.internal.xsom.impl.scd.Iterators.Map;

public class Flow {
	int flowid;
	OFMatch match;
	NodePortTuple src;
	NodePortTuple dst;
	
	HashMap<Policy, OFMatch> policies;
	
	public Flow(OFMatch m, NodePortTuple srcNodePort, NodePortTuple dstNodePort){
		match = m;
		policies = new HashMap<Policy, OFMatch>();
		src = srcNodePort;
		dst = dstNodePort;
		flowid = this.hashCode();
	}
	
	public HashMap<Policy, OFMatch> getPoliy(){
		return this.policies;
	}
	
	public void addPolicy(Policy policy){
		this.policies.add(policy);
	}
	
	
	public int hashCode(){
		return match.hashCode();
	}


	public Route getRoute() {
		// TODO Auto-generated method stub
		return null;
	}
}

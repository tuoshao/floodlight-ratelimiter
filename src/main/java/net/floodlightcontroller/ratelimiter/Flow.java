package net.floodlightcontroller.ratelimiter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.NodePortTuple;

import org.openflow.protocol.OFMatch;
import org.openflow.util.HexString;

public class Flow {
	int flowid;
	OFMatch match;
	NodePortTuple src;
	NodePortTuple dst;
	
	List<Integer> policies;
	
	public Flow(OFMatch m, NodePortTuple srcNodePort, NodePortTuple dstNodePort){
		match = m;
		policies = new ArrayList<Integer>();
		src = srcNodePort;
		dst = dstNodePort;
		flowid = this.hashCode();
	}
	
	public void addPolicy(int id){
		this.policies.add(Integer.valueOf(id));
	}
	
	
	public int hashCode(){
		return match.hashCode();
	}


	public Route getRoute() {
		// TODO Auto-generated method stub
		return null;
	}
}

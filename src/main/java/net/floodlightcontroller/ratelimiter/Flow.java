package net.floodlightcontroller.ratelimiter;

import java.util.*;

import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.NodePortTuple;

import org.openflow.protocol.OFMatch;

public class Flow {
	int flowid;
	OFMatch match;
    NodePortTuple src;
	NodePortTuple dst;

    Set<Route> routes;
    //OFMatch for adding queue on the target switch
	HashMap<Policy, OFMatch> policies;
	
	public Flow(OFMatch m, NodePortTuple srcNodePort, NodePortTuple dstNodePort){
		match = m;
		policies = new HashMap<Policy, OFMatch>();
		
        routes = new HashSet<Route>();
		src = srcNodePort;
		dst = dstNodePort;
		flowid = this.hashCode();
	}
	
	public Set<Policy> getPolicies(){
		return policies.keySet();
	}

    public void addPolicy(Policy policy) {
        addPolicy(policy, null);
    }
	
	public void addPolicy(Policy policy, OFMatch qmatch){
		policies.put(policy, qmatch);
	}

    public void clearRoute() {
        routes.clear();
    }

    public void addRoute(Route route) {
        routes.add(route);
    }
	
	public int hashCode(){
		return match.hashCode();
	}

    public void setQmatch(Policy p, OFMatch qm) {
        policies.put(p, qm);
    }

    public OFMatch getQmatch(Policy p) {
        return policies.get(p);
    }

    public NodePortTuple getSrc() {
        return src;
    }

    public NodePortTuple getDst() {
        return dst;
    }

    public OFMatch getMatch() {
        return match;
    }

    public Set<Route> getRoutes() {
		return routes;
	}
}

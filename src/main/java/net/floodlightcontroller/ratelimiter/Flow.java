package net.floodlightcontroller.ratelimiter;

import java.util.*;

import net.floodlightcontroller.topology.NodePortTuple;

import org.openflow.protocol.OFMatch;

public class Flow {
	int flowid;
	OFMatch match;
    NodePortTuple src;
	NodePortTuple dst;
    List<Policy> policies;
    List<NodePortTuple> queues;
    //OFMatch for adding queue on the target switch
	List<OFMatch> qmatches;
	
	public Flow(OFMatch m, NodePortTuple srcNodePort, NodePortTuple dstNodePort){
		match = m;
        policies = new ArrayList<Policy>();
        queues = new ArrayList<NodePortTuple>();
        qmatches = new ArrayList<OFMatch>();
		src = srcNodePort;
		dst = dstNodePort;
		flowid = this.hashCode();
	}

    public NodePortTuple lastSwExceptPolicy(Policy p) {
        for (int i = policies.size()-1; i >= 0; i--) {
            if (p.hashCode() != policies.get(i).hashCode()) {
                return queues.get(i);
            }
        }
        return src;
    }

    public void removeQueueForPolicy(Policy p) {
        int index = policies.indexOf(p);
        if (index >= 0) {
            queues.remove(index);
            qmatches.remove(index);
            policies.remove(index);
        }
    }
	
	public List<Policy> getPolicies(){
		return policies;
	}


	public void addPolicy(Policy p, OFMatch qmatch){
        if (policies.contains(p)) {
            int index = policies.indexOf(p);
            queues.set(index, new NodePortTuple(p.getDpid(), p.getPort()));
            qmatches.set(index, qmatch);
        } else {
            policies.add(p);
            queues.add(new NodePortTuple(p.getDpid(), p.getPort()));
            qmatches.add(qmatch);
        }
	}

	public int hashCode(){
		return match.hashCode();
	}

    public void setQmatch(Policy p, OFMatch qm) {
        int index = policies.indexOf(p);
        qmatches.set(index, qm);
    }

    public OFMatch getQmatch(Policy p) {
        int index = policies.indexOf(p);
        return qmatches.get(index);
    }

    public List<OFMatch> getQmatches() {
        return qmatches;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Flow flow = (Flow) o;

        if (!match.equals(flow.match)) return false;

        return true;
    }
}

package net.floodlightcontroller.ratelimiter;

import java.io.IOException;
import java.util.*;

import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.*;

import org.openflow.protocol.*;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionEnqueue;
import org.openflow.protocol.action.OFActionType;
import org.openflow.util.HexString;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.counter.ICounterStoreService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.forwarding.Forwarding;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.benchmarkcontroller.IQueueCreaterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RateLimiterController extends Forwarding implements RateLimiterService{
    // number of queues on a switch
    private static final int NQUEUE = 3;

	private Map<Integer, Policy> policyStorage;
	private Map<Integer, Flow> flowStorage;
    private static Logger log = LoggerFactory.getLogger(RateLimiterController.class);
    private ILinkDiscoveryService linkService;
    protected IQueueCreaterService queueCreaterService;
	protected IRestApiService restApi;
    private Map<Long, IOFSwitch> switches;
    private Map<NodePortTuple, Set<Policy>> swPolicyTuples;

	public boolean flowBelongsToRule(OFMatch flow, OFMatch rule){
        log.warn("flow: " + flow.toString() + " rule: " + rule.toString());

		int rulewc = rule.getWildcards();
		if(!(((rulewc & OFMatch.OFPFW_IN_PORT) == OFMatch.OFPFW_IN_PORT) || 
				rule.getInputPort() == flow.getInputPort()))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_DL_VLAN) == OFMatch.OFPFW_DL_VLAN) || 
				rule.getDataLayerVirtualLan() == flow.getDataLayerVirtualLan()))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_DL_SRC) == OFMatch.OFPFW_DL_SRC) || 
				Arrays.equals(rule.getDataLayerSource(), flow.getDataLayerSource())))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_DL_DST) == OFMatch.OFPFW_DL_DST) || 
				Arrays.equals(rule.getDataLayerDestination(), flow.getDataLayerDestination())))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_DL_TYPE) == OFMatch.OFPFW_DL_TYPE) || 
				rule.getDataLayerType() == flow.getDataLayerType()))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_NW_PROTO) == OFMatch.OFPFW_NW_PROTO) || 
				rule.getNetworkProtocol() == flow.getNetworkProtocol()))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_TP_SRC) == OFMatch.OFPFW_TP_SRC) || 
				rule.getTransportSource() == flow.getTransportSource()))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_TP_DST) == OFMatch.OFPFW_TP_DST) || 
				rule.getTransportDestination() == flow.getTransportDestination()))
			return false;
		
		int ruleSrcMask = rule.getNetworkSourceMaskLen();
		int matchSrcMask = flow.getNetworkSourceMaskLen();
		
		if(!(ruleSrcMask <= matchSrcMask &&
				(rule.getNetworkSource() & ((ruleSrcMask==0)? 0:0xffffffff << (32-ruleSrcMask))) ==
				(flow.getNetworkSource() & ((ruleSrcMask==0)? 0:0xffffffff << (32-ruleSrcMask)))))
			return false;
		int ruleDstMask = rule.getNetworkDestinationMaskLen();
		int matchDstMask = flow.getNetworkDestinationMaskLen();
		if(!(ruleDstMask <= matchDstMask &&
				(rule.getNetworkDestination() & ((ruleDstMask==0)? 0:0xffffffff << (32-ruleDstMask))) ==
				(flow.getNetworkDestination() & ((ruleDstMask==0)? 0:0xffffffff << (32-ruleDstMask)))))
			return false;

		return true;
	}

    // return the qort number if available, return neg if not
    private synchronized int isSwitchAvaiForPolicy(Policy p, NodePortTuple sw) {
        //log.warn("isSwitchAvaiForPolicy:" + sw.toString());
        Set<Policy> pset;
        if (!swPolicyTuples.containsKey(sw)) {
            pset = new HashSet<Policy>();
            swPolicyTuples.put(sw, pset);
            return 1;
        }

        Set<Integer> usedQueue = new HashSet<Integer>();
        pset = swPolicyTuples.get(sw);
        for (Policy ponsw : pset) {
            if (ponsw.getSwport() == null || ponsw.equals(p)) {
                /* TODO error-prone */
                pset.remove(ponsw);
                continue;
            }
            ArrayList<OFMatch> ms1 = new ArrayList<OFMatch>(ponsw.getRules());
            ArrayList<OFMatch> ms2 = new ArrayList<OFMatch>(p.getRules());
            //log.warn("Check policies: " + ms1.get(0) + ms2.get(0));
            if (checkIfPolicyCoexist(p, ponsw)) {
                //log.warn("overlap flow exists");
                return -1;
            }
            usedQueue.add(ponsw.getQueue());
        }

        if (pset.size() >= NQUEUE) {
            return -1;
        }
        int queue = -1;
        for (int i = 1; i <= NQUEUE; i++) {
            if (!usedQueue.contains(i)) {
                queue = 1;
                break;
            }
        }
        return queue;
    }
	
	private boolean checkIfPolicyCoexist(Policy p1, Policy p2){
		for(Flow flowp1:p1.flows){
			for(OFMatch rule:p2.rules){
				if(flowBelongsToRule(flowp1.match, rule)) {
					return true;
				}
			}
		}
		
		for(Flow flowp2:p2.flows){
			for(OFMatch rule:p1.rules){
				if(flowBelongsToRule(flowp2.match, rule)){
					return true;
				}
			}
		}
		return false;
	}

	private Set<Policy> matchPoliciesFromStorage(OFMatch match){
		Set<Policy> matchedPolicies= new HashSet<Policy>();
		Iterator itp = policyStorage.values().iterator();
		while(itp.hasNext()){
			Policy policytmp = (Policy) itp.next();
			if(policytmp.flows.contains(Integer.valueOf(match.hashCode())))
				continue;
			Iterator itr = policytmp.rules.iterator();
			while(itr.hasNext()){
				if(flowBelongsToRule(match, (OFMatch) itr.next())) {
					matchedPolicies.add(policytmp);
                }
			}
			
		}
		return matchedPolicies;
	}

	private synchronized boolean processPacket(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx){
        IDevice dstDevice =
                IDeviceService.fcStore.
                        get(cntx, IDeviceService.CONTEXT_DST_DEVICE);
        IDevice srcDevice =
                IDeviceService.fcStore.
                        get(cntx, IDeviceService.CONTEXT_SRC_DEVICE);

        //We can't handle packets with unknown destination
        if (dstDevice == null || srcDevice == null) {
            return false;
        }

        SwitchPort[] dstDaps = dstDevice.getAttachmentPoints();
        SwitchPort[] srcDaps = srcDevice.getAttachmentPoints();
        if (dstDaps == null || srcDaps == null) return false;
        SwitchPort dstsw = dstDaps[0];
        SwitchPort srcsw = srcDaps[0];

		OFMatch match = new OFMatch();
		match.loadFromPacket(pi.getPacketData(), pi.getInPort());

        NodePortTuple srctuple = new NodePortTuple(srcsw.getSwitchDPID(), srcsw.getPort());
        NodePortTuple dsttuple = new NodePortTuple(dstsw.getSwitchDPID(), dstsw.getPort());
        OFMatch flowmatch = match.clone();
        flowmatch.setInputPort((short) 0);
        Flow flow = new Flow(flowmatch, srctuple, dsttuple);

        Set<Policy> policies = matchPoliciesFromStorage(match);
        log.warn("This flow matches " + policies.size() + " policies ");
        if(policies.isEmpty()) {
        	Integer wildcard_hints = null;
            IRoutingDecision decision = null;
            if (cntx != null) {
                decision = IRoutingDecision.rtStore
                        .get(cntx,
                                IRoutingDecision.CONTEXT_DECISION);
            }
            if (decision != null) {
                wildcard_hints = decision.getWildcards();
            } else {
            	// L2 only wildcard if there is no prior route decision
                wildcard_hints = ((Integer) sw
                        .getAttribute(IOFSwitch.PROP_FASTWILDCARDS))
                        .intValue()
                        & ~OFMatch.OFPFW_IN_PORT
                        & ~OFMatch.OFPFW_DL_VLAN
                        & ~OFMatch.OFPFW_DL_SRC
                        & ~OFMatch.OFPFW_DL_DST
                        & ~OFMatch.OFPFW_NW_SRC_MASK
                        & ~OFMatch.OFPFW_NW_DST_MASK;
            }
            flow.match.setWildcards(wildcard_hints.intValue());
            flowStorage.put(flow.hashCode(), flow);

            return false;
        }
        flowStorage.put(flow.hashCode(), flow);

        for (Policy p : policies) {
            SwitchPort oldsw = p.getSwport();
            Map<Flow, OFMatch> oldqmatch = new HashMap<Flow, OFMatch>();
            p.getFLows().remove(flow);
            if (!p.getFLows().isEmpty()) {
                for (Flow f : p.getFLows()) {
                    oldqmatch.put(f, f.getQmatch(p));
                }
            }

            Map<Flow, Integer> newSw = findNewSwitch(p, flow);
            if (newSw != null) {
                NodePortTuple nsw = new NodePortTuple(p.getDpid(), p.getPort());
                if (!swPolicyTuples.containsKey(nsw)) {
                    swPolicyTuples.put(nsw, new HashSet<Policy>());
                }
                swPolicyTuples.get(nsw).add(p);
                if (oldsw != null) {
                    swPolicyTuples.get(new NodePortTuple(oldsw.getSwitchDPID(), oldsw.getPort())).remove(p);
                }
                for (Flow f : p.getFLows()) {
                    if (f.equals(flow)) {
                        continue;
                    }
                    if (oldsw != null) {
                        //Delete enqueue and queue
                        SwitchPort newsw = p.getSwport();
                        p.setSwport(oldsw);
                        installMatchedFLowToSwitch(oldqmatch.get(f), p, OFFlowMod.OFPFC_DELETE_STRICT);
                        p.setSwport(newsw);
                    }
                    //Add new routes and enqueue
                    addRouteByPolicyAndFlow(sw, pi, cntx, p, f, newSw.get(f));
                }
                addRouteByPolicyAndFlow(sw, pi, cntx, p, flow, newSw.get(flow));
            } else {
                ArrayList<Integer> newflowarray = canFlowRouteToSw(flow, new NodePortTuple(p.getDpid(), p.getPort()), p);
                if(newflowarray != null){
                	addRouteByPolicyAndFlow(sw, pi, cntx, p, flow, newflowarray.get(0));
                } else {
                	log.warn("Cant route to switch, delete policy|||||||||||||");
                	deletePolicy(p);
                }
            }
        }

		return true;
	}

    private void addRouteByPolicyAndFlow(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx, Policy p, Flow f, int index) {
        OFMatch match = f.getMatch();
        NodePortTuple src, dst;
        if (index == 0) {
            src = f.getSrc();
        } else {
            src = findNextSwitchPort(f.getQueues().get(index-1));
        }

        /* Add the route before the queue */
        short qsinport;
        Route r1_temp = routingEngine.getRoute(src.getNodeId(), src.getPortId(), p.getDpid(), p.getPort(), 0);
        Route r1 = new Route(r1_temp.getId(), r1_temp.getPath());
        int r1len = r1.getPath().size();
        r1.getPath().remove(r1len-1);
        qsinport = r1.getPath().get(r1len-2).getPortId();
        r1.getPath().remove(r1len-2);
        long cookie1 = AppCookie.makeCookie(FORWARDING_APP_ID, 0);
        pushRoute(r1, match, match.getWildcards(), pi, sw.getId(), cookie1,
                cntx, false, true, OFFlowMod.OFPFC_MODIFY_STRICT);
        log.warn("Push Route1: " + r1.toString());

        if (index == f.getPolicies().size()) {
            dst = f.getDst();
        } else {
            dst = f.getQueues().get(index);
        }

        /* Add the route after the queue (if necessary) */
        NodePortTuple nexts = findNextSwitchPort(new NodePortTuple(p.getDpid(), p.getPort()));
        if (nexts != null) {
            Route r2 = routingEngine.getRoute(nexts.getNodeId(), nexts.getPortId(), dst.getNodeId(), dst.getPortId(), 0);
            if (index != f.getPolicies().size()) {
                int r2len = r2.getPath().size();
                r2.getPath().remove(r2len-1);
                r2.getPath().remove(r2len-2);
            }

            if (r2.getPath().size() > 0) {
                long cookie2 = AppCookie.makeCookie(FORWARDING_APP_ID, 0);
                pushRoute(r2, match, match.getWildcards(), pi, sw.getId(), cookie2,
                        cntx, false, true, OFFlowMod.OFPFC_MODIFY_STRICT);
                log.warn("Push Route2: " + r2.toString());
            }
        }

        /* Add the enqueue entry */
        OFMatch s2match = match.clone();
        s2match.setInputPort(qsinport);
        log.warn("Push Queue: " + s2match.toString());
        installMatchedFLowToSwitch(s2match, p, OFFlowMod.OFPFC_MODIFY_STRICT);

        f.addPolicyAtIndex(p, s2match, index);
    }

    private void addRouteAndEnqueue(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx, Policy p, Flow f) {
        OFMatch match = f.getMatch();
        NodePortTuple src = f.lastSwExceptPolicy(p);
        NodePortTuple qs = new NodePortTuple(p.getDpid(), p.getPort());
        short qsinport;
        /* Add the route before the queue */
        Route r1_temp = routingEngine.getRoute(src.getNodeId(), src.getPortId(), p.getDpid(), p.getPort(), 0);
        if (r1_temp != null) {
            Route r1 = new Route(r1_temp.getId(), r1_temp.getPath());
            int r1len = r1.getPath().size();
            r1.getPath().remove(r1len-1);
            qsinport = r1.getPath().get(r1len-2).getPortId();
            r1.getPath().remove(r1len-2);
            long cookie1 = AppCookie.makeCookie(FORWARDING_APP_ID, 0);
            pushRoute(r1, match, match.getWildcards(), pi, sw.getId(), cookie1,
                    cntx, false, true, OFFlowMod.OFPFC_MODIFY_STRICT);
        } else {
            qsinport = pi.getInPort();
        }

        /* Add the route after the queue (if necessary) */
        Set<Link> links = linkService.getPortLinks().get(qs);
        if (links != null) {
            NodePortTuple nexts = null;
            for (Link l : links) {
                if (l.getSrc() == qs.getNodeId() && l.getSrcPort() == qs.getPortId()) {
                    nexts = new NodePortTuple(l.getDst(), l.getDstPort());
                    break;
                }
            }
            Route r2 = routingEngine.getRoute(nexts.getNodeId(), nexts.getPortId(), f.getDst().getNodeId(), f.getDst().getPortId(), 0);
            long cookie2 = AppCookie.makeCookie(FORWARDING_APP_ID, 0);
            pushRoute(r2, match, match.getWildcards(), pi, sw.getId(), cookie2,
                    cntx, false, true, OFFlowMod.OFPFC_MODIFY_STRICT);
        }

        /* Add the enqueue entry */
        OFMatch s2match = match.clone();
        s2match.setInputPort(qsinport);
        installMatchedFLowToSwitch(s2match, p, OFFlowMod.OFPFC_MODIFY_STRICT);

        f.addPolicy(p, s2match);
    }

    // install queue and enqueue entry
	private void installMatchedFLowToSwitch(OFMatch flow, Policy p, short flowModCommand){
        IOFSwitch sw = switches.get(p.getDpid());
        if(flowModCommand != OFFlowMod.OFPFC_DELETE_STRICT) {
            queueCreaterService.createQueue(sw, p.getPort(), p.queue, p.speed);
            log.warn("Create queue on " + sw.toString() + p.getSwport().toString());
        } else {
            queueCreaterService.deleteQueue(sw, p.getPort(), p.queue);
            log.warn("Delete queue on " + sw.toString() + p.getSwport().toString());
        }

        OFFlowMod fm = new OFFlowMod();
        fm.setType(OFType.FLOW_MOD);

        List<OFAction> actions = new ArrayList<OFAction>();

        //add the queuing action
        OFActionEnqueue enqueue = new OFActionEnqueue();
        enqueue.setLength((short)OFActionEnqueue.MINIMUM_LENGTH);
        enqueue.setType(OFActionType.OPAQUE_ENQUEUE); // I think this happens anyway in the constructor
        //enqueue.setPort(p.getPort());
        enqueue.setQueueId(p.queue);

        if(flow.getInputPort() == p.getPort()){
            fm.setOutPort(OFPort.OFPP_IN_PORT);
            enqueue.setPort(OFPort.OFPP_IN_PORT.getValue());
        } else {
            fm.setOutPort(OFPort.OFPP_NONE);
            enqueue.setPort(p.getPort());
        }

        actions.add(enqueue);
        long cookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);
        fm.setMatch(flow)
                .setCookie(cookie)
                .setCommand(flowModCommand)
                .setActions(actions)
                .setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)  // infinite
                .setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)  // infinite
                .setBufferId(OFPacketOut.BUFFER_ID_NONE)
                .setFlags(OFFlowMod.OFPFF_SEND_FLOW_REM)
                        //.setOutPort(OFPort.OFPP_NONE.getValue())
                .setPriority(p.priority)
                .setLengthU(OFFlowMod.MINIMUM_LENGTH+OFActionEnqueue.MINIMUM_LENGTH);
        try {
            sw.write(fm, null);
            sw.flush();
        } catch (IOException e) {
            log.error("Tried to write OFFlowMod to {} but failed: {}",
                    HexString.toHexString(sw.getId()), e.getMessage());
        }

				
	}

    private NodePortTuple findNextSwitchPort(NodePortTuple src) {
        Set<Link> links = linkService.getPortLinks().get(src);
        if (links == null) return null;
        for (Link l : links) {
            if (l.getSrc() == src.getNodeId() && l.getSrcPort() == src.getPortId()) {
                NodePortTuple next = new NodePortTuple(l.getDst(), l.getDstPort());
                return next;
            }
        }
        return null;
    }

    private Set<NodePortTuple> canPassSwBetweenSws(NodePortTuple src, NodePortTuple dst, NodePortTuple sw) {
        Set<NodePortTuple> set1 = new HashSet<NodePortTuple>();
        Set<NodePortTuple> set2 = new HashSet<NodePortTuple>();

        Route subr1 = routingEngine.getRoute(src.getNodeId(), src.getPortId(), sw.getNodeId(), sw.getPortId(), 0);
        if (subr1 == null) {
            log.warn("no route 1");
            return null;
        } else if (subr1.getPath().get(0).equals(subr1.getPath().get(1))) {
            /* can't go back to the inport */
            log.warn("cant go back to inport1");
            return null;
        }

        for (int i = 0; i < subr1.getPath().size(); i++) {
            if (i % 2 == 0) {
                set1.add(subr1.getPath().get(i));
            }
        }

        NodePortTuple next = findNextSwitchPort(sw);
        if (next == null) {
            if (sw.equals(dst)) {
                return set1;
            } else {
            	log.warn("!equal dst");
                return null;
            }
        }

        Route subr2 = routingEngine.getRoute(next.getNodeId(), next.getPortId(), dst.getNodeId(), dst.getPortId(), 0);
        if (subr2 == null) {
            log.warn("no route 2");
            return null;
        } else if (subr2.getPath().get(0).equals(subr2.getPath().get(1))) {
            /* can't go back to the inport */
            log.warn("cant go back to inport" + subr2.getPath().get(0).toString() + subr2.getPath().get(1).toString());
            return null;
        }

        for (int i = 0; i < subr2.getPath().size(); i++) {
            if (i % 2 == 0) {
                set2.add(subr2.getPath().get(i));
            }
        }

        for (NodePortTuple np : set1) {
            /* check if there is any incoming switch port tuple reused */
            /* maybe this is redundant */
            if (set2.contains(np)) {
            	log.warn("redundant");
            	return null;
            }
        }

        set1.addAll(set2);

        return set1;
    }

    // return an ArrayList<Integer>, with format of {new index, overhead}, return null if can't route
    private ArrayList<Integer> canFlowRouteToSw(Flow flow, NodePortTuple sw, Policy p) {
        log.warn("call canFlowRouteToSw on " + sw.toString() + p.getRules().toString());
        log.warn("flow:" + flow.getMatch().toString() + " has " + flow.getPolicies().size() /*+ " policies at " + flow.getQueues().get(0)*/ + "\n");
        NodePortTuple src = flow.getSrc();
        NodePortTuple next = null;
        Set<NodePortTuple> srcs;
        ArrayList<Integer> ret = new ArrayList<Integer>();

        if (flow.getPolicies().isEmpty()) {
            srcs = canPassSwBetweenSws(src, flow.getDst(), sw);
            if (srcs != null) {
                ret.add(0);
                ret.add(srcs.size());
                log.warn("insert sw at " + 0 + " overhead " + srcs.size());
                return ret;
            } else {
                log.warn("fail because from " + src.toString() + " to " + flow.getDst().toString() + " via " + sw.toString());
                return null;
            }
        } else {
            for (int i = 0; i < flow.getPolicies().size(); i++) {
                next = flow.getQueues().get(i);
                srcs = canPassSwBetweenSws(src, next, sw);
                if (srcs != null) {
                    log.warn("insert sw at " + i + " overhead " + srcs.size());
                    ret.add(i);
                    ret.add(srcs.size());
                    return ret;
                }
                src = findNextSwitchPort(next);
                if (src == null) {
                    log.warn("fail because from " + src.toString() + " to " + next.toString());
                    return null;
                }
            }
            srcs = canPassSwBetweenSws(src, flow.getDst(), sw);
            if (srcs != null) {
                ret.add(flow.getPolicies().size());
                ret.add(srcs.size());
                log.warn("insert sw at " + flow.getPolicies().size() + " overhead " + srcs.size());
                return ret;
            }
        }
        log.warn("fail because from " + src.toString() + " to " + flow.getDst().toString());
        return null;
    }

    private int canFlowPassSw(Flow flow, NodePortTuple sw, Policy p) {
        NodePortTuple src = flow.lastSwExceptPolicy(p);
        NodePortTuple dst = flow.getDst();
        //log.warn("flow lastsrc: " + src.toString());
        //log.warn("flow dst: " + dst.toString());
        //log.warn("sw: " + sw.toString());

        NodePortTuple nexts1 = null;
        if (!src.equals(flow.getSrc())) {
            Set<Link> links1 = linkService.getPortLinks().get(src);
            if (links1 == null) return -1;
            for (Link l : links1) {
                if (l.getSrc() == src.getNodeId() && l.getSrcPort() == src.getPortId()) {
                    nexts1 = new NodePortTuple(l.getDst(), l.getDstPort());
                    break;
                }
            }
        } else {
            nexts1 = src;
        }

        Route subr1 = routingEngine.getRoute(nexts1.getNodeId(), nexts1.getPortId(), sw.getNodeId(), sw.getPortId(), 0);
        if (subr1 != null) {
            if (subr1.getPath().get(0).equals(subr1.getPath().get(1))) {
                return -1;
            }
            if (sw.equals(dst)) {
                return subr1.getPath().size();
            }
        }

        Set<Link> links = linkService.getPortLinks().get(sw);
        if (links == null) return -1;
        NodePortTuple nexts = null;
        for (Link l : links) {
            if (l.getSrc() == sw.getNodeId() && l.getSrcPort() == sw.getPortId()) {
                nexts = new NodePortTuple(l.getDst(), l.getDstPort());
                break;
            }
        }
        Route subr2 = routingEngine.getRoute(nexts.getNodeId(), nexts.getPortId(), dst.getNodeId(), dst.getPortId(), 0);
        if (subr1 == null || subr2 == null) {
            /* If this switch can't reach either a flow's dst or src, it's not available */
            //log.warn("no route");
            return -1;
        }
        //log.warn("route1" + subr1.getPath().toString());
        //log.warn("route2" + subr2.getPath().toString());

        if (subr2.getPath().get(0).equals(subr2.getPath().get(1))) {
            return -1;
        }

        /* Check if incoming route and outgoing route shares the same link */
        if (subr2 != null) {
            int len1 = subr1.getPath().size(), len2 = subr2.getPath().size();
            List<NodePortTuple> path1 = new ArrayList<NodePortTuple>(), path2 = new ArrayList<NodePortTuple>();
            for (int i = 0; i < len1; i++) {
                if (i % 2 == 0) {
                    path1.add(subr1.getPath().get(i));
                }
            }
            for (int i = 0; i < len2; i++) {
                if (i % 2 == 0) {
                    path2.add(subr2.getPath().get(i));
                }
            }
            for (NodePortTuple npt : path1) {
                if (path2.contains(npt)) {
                    //log.warn("no route(overlap)" + npt.toString());
                    return -1;
                }
            }
            for (NodePortTuple npt : path2) {
                if (path1.contains(npt)) {
                    //log.warn("no route(overlap)" + npt.toString());
                    return -1;
                }
            }
        }
        int routelen = 0;
        routelen += subr1.getPath().size();
        if (subr2 != null) {
            routelen += subr2.getPath().size();
        }
        //log.warn("has route");
        return routelen;
    }

    /* This function checks if the policy needs to change the switch */
	private synchronized Map<Flow, Integer> findNewSwitch(Policy p, Flow flow) {
        log.warn("Find new switch for " + p.getRules().toString() + flow.getMatch().toString());
        Map<Flow, Integer> ret = new HashMap<Flow, Integer>();
        p.addFlow(flow);
        NodePortTuple src, dst, next;
        src = flow.lastSwExceptPolicy(p);
        dst = flow.getDst();
        Route r = routingEngine.getRoute(src.getNodeId(), src.getPortId(), dst.getNodeId(), dst.getPortId(), 0);
        if (r == null) {
            /* TODO There is no route for this flow, should return error */
            return null;
        } else if (p.getFLows().isEmpty() || p.getSwport() == null) {
            /* If this is the first flow matched by this policy, we add a queue at the first hop */
            //log.warn("First flow of this matched policy");
            next = r.getPath().get(1);
            int queue = isSwitchAvaiForPolicy(p, next);
            if (queue > 0) {
                p.setQueue(queue);
                p.setSwport(new SwitchPort(next.getNodeId(), next.getPortId()));
                ret.put(flow, 0);
                return ret;
            }
        } else if (p.getSwport() != null) {
            NodePortTuple qs = new NodePortTuple(p.getDpid(), p.getPort());
            log.warn("Check old sw:" + qs.toString());
            if (r.getPath().contains(qs)) {
                /* If the new flow's default route contains the queue switch, there is no need to change it */
                return null;
            } else if (canFlowRouteToSw(flow, qs, p) != null) {
                return null;
            }
        }
        log.warn("Need to find a new switch");
        /* TODO If there's no overlapped switch, we need to find a new switch that can satisfy all flows (IMPORTANT!!!) */

        for (Flow f : p.getFLows()) {
            f.removeQueueForPolicy(p);
        }

        NodePortTuple bestsp = null;
        int bestroute = Integer.MAX_VALUE, bestqueue = 0;
        Map<Flow, ArrayList<Integer>> bestflowindex = null;
        for (IOFSwitch sw : switches.values()) {
            for (OFPhysicalPort po : sw.getPorts()) {
                NodePortTuple sp = new NodePortTuple(sw.getId(), po.getPortNumber());
                Map<Flow, ArrayList<Integer>> flowindex = new HashMap<Flow, ArrayList<Integer>>();
                //debugging
                if (sp.getPortId() < 0) {
                    /* Don't check negative ports (perhaps for controller connection) */
                    continue;
                }
                int queue = isSwitchAvaiForPolicy(p, sp);
                if (queue < 0) {
                    continue;
                }

                boolean available = true;
                int routelen = 0, rl;
                for (Flow f : p.getFLows()) {
                    ArrayList<Integer> result = canFlowRouteToSw(f, sp, p);
                    if (result == null) {
                        available = false;
                        break;
                    }
                    routelen += result.get(1);
                    flowindex.put(f, result);
                }
                if (available) {
                    log.warn(sp.toString() + " is OK");
                } else {
                    log.warn(sp.toString() + " is not OK");
                }
                if (available && routelen < bestroute) {
                    log.warn(sp.toString() + " is better, overhead " + routelen);
                    bestsp = sp;
                    bestroute = routelen;
                    bestqueue = queue;
                    bestflowindex = flowindex;
                }
            }
        }
        if (bestsp == null) {
            /* TODO There is no available switch, should return error */
            log.warn("No available switch in findNewSwitch()");
            return null;
        } else {
            p.setSwport(new SwitchPort(bestsp.getNodeId(), bestsp.getPortId()));
            p.setQueue(bestqueue);
            for (Flow f : bestflowindex.keySet()) {
                ret.put(f, bestflowindex.get(f).get(0));
            }
            return ret;
        }
        /* TODO Need to handle if the target switch already has a policy, and check if the policies conflict */
	}

    @Override
    protected void doForwardFlow(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx, boolean requestFlowRemovedNotifn) {
        if (processPacket(sw, pi, cntx)) return;
        else super.doForwardFlow(sw, pi, cntx, requestFlowRemovedNotifn);
    }
    
    private synchronized Command handleFlowRemoved(IOFSwitch sw, OFFlowRemoved msg, FloodlightContext cntx) {
        OFMatch match = msg.getMatch();
        match.setInputPort((short) 0);
        if (!flowStorage.containsKey(match.hashCode())) {
            return Command.CONTINUE;
        } else {
            Flow flow = flowStorage.get(match.hashCode());
            for (Policy p : flow.getPolicies()) {
                p.getFLows().remove(flow);
                if (p.getFLows().isEmpty()) {
                    //queueCreaterService.deleteQueue(switches.get(p.getDpid()), p.getPort(), p.queue);
                	if(p.getSwport() != null) {
	                    Set<Policy> setp = swPolicyTuples.get(new NodePortTuple(p.getDpid(), p.getPort()));
	                    if (setp != null) {
	                        setp.remove(p);
	                    }
	                    p.setSwport(null);
                		
                	}
                }
            }
            log.warn("Remove Flow: " + flow.getMatch().toString());
            flowStorage.remove(flow);
            return Command.CONTINUE;
        }
    }
    
    @Override
    public Command receive(IOFSwitch sw, OFMessage msg,
                           FloodlightContext cntx) {
        switch (msg.getType()) {
            case PACKET_IN:
                IRoutingDecision decision = null;
                if (cntx != null)
                     decision =
                             IRoutingDecision.rtStore.get(cntx,
                                                          IRoutingDecision.CONTEXT_DECISION);

                return this.processPacketInMessage(sw,
                                                   (OFPacketIn) msg,
                                                   decision,
                                                   cntx);
            case FLOW_REMOVED:
                return handleFlowRemoved(sw, (OFFlowRemoved) msg, cntx);
            default:
                break;
        }
        return Command.CONTINUE;
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
    	super.startUp(context);
    	restApi.addRestletRoutable(new RateLimiterWeb());	
        floodlightProvider.addOFMessageListener(OFType.FLOW_REMOVED, this);
    }
    
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        // We don't export any services
    	Collection<Class<? extends IFloodlightService>> l = 
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(RateLimiterService.class);
        return l;
    }
    
    @Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>,
        IFloodlightService> m = 
        new HashMap<Class<? extends IFloodlightService>,
        IFloodlightService>();
        // We are the class that implements the service
        m.put(RateLimiterService.class, this);
        return m;
	}
    
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        super.init();
        this.floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        this.deviceManager = context.getServiceImpl(IDeviceService.class);
        this.routingEngine = context.getServiceImpl(IRoutingService.class);
        this.topology = context.getServiceImpl(ITopologyService.class);
        this.counterStore = context.getServiceImpl(ICounterStoreService.class);
        this.linkService = context.getServiceImpl(ILinkDiscoveryService.class);
        this.queueCreaterService = context.getServiceImpl(IQueueCreaterService.class);
        this.restApi = context.getServiceImpl(IRestApiService.class);
        this.policyStorage = new HashMap<Integer, Policy>();
    	this.flowStorage = new HashMap<Integer, Flow>();
    	
        switches = floodlightProvider.getSwitches();
        swPolicyTuples = new HashMap<NodePortTuple, Set<Policy>>();
        for (IOFSwitch s : switches.values()) {
            for (OFPhysicalPort po : s.getPorts()) {
                NodePortTuple nnpt = new NodePortTuple(s.getId(), po.getPortNumber());
                Set<Policy> concurSet = Collections.synchronizedSet(new HashSet<Policy>());
                swPolicyTuples.put(nnpt, concurSet);
            }
        }

        /*OFMatch temp_match = new OFMatch(), temp2 = new OFMatch();
        temp_match.setWildcards(~(OFMatch.OFPFW_NW_DST_MASK));
        temp_match.setNetworkDestination(167772164);
        temp2.setWildcards(~(OFMatch.OFPFW_NW_SRC_MASK));
        temp2.setNetworkSource(167772161);

        Set<OFMatch> temp_policyset = new HashSet<OFMatch>(), temp2_set = new HashSet<OFMatch>();
        temp_policyset.add(temp_match);
        temp2_set.add(temp2);
        Policy temp_policy = new Policy(temp_policyset, (short)1);
        Policy temp2_pol = new Policy(temp2_set, (short) 1);
        //temp_policy.setQueue(1);
        //temp2_pol.setQueue(1);
        log.info("policyid: " + temp_policy.policyid);
        for(OFMatch match:temp_policy.getRules()){
        	log.info(match.toString());
        }
        policyStorage.put(Integer.valueOf(temp_policy.hashCode()), temp_policy);
        policyStorage.put(Integer.valueOf(temp2_pol.hashCode()), temp2_pol);*/

        // read our config options
        Map<String, String> configOptions = context.getConfigParams(this);
        try {
            String idleTimeout = configOptions.get("idletimeout");
            if (idleTimeout != null) {
                FLOWMOD_DEFAULT_IDLE_TIMEOUT = 2;//Short.parseShort(idleTimeout);
            }
        } catch (NumberFormatException e) {
            log.warn("Error parsing flow idle timeout, " +
            		 "using default of {} seconds",
                     FLOWMOD_DEFAULT_IDLE_TIMEOUT);
        }
        try {
            String hardTimeout = configOptions.get("hardtimeout");
            if (hardTimeout != null) {
                FLOWMOD_DEFAULT_HARD_TIMEOUT = Short.parseShort(hardTimeout);
            }
        } catch (NumberFormatException e) {
            log.warn("Error parsing flow hard timeout, " +
            		 "using default of {} seconds",
                     FLOWMOD_DEFAULT_HARD_TIMEOUT);
        }
        log.debug("FlowMod idle timeout set to {} seconds", 
                  FLOWMOD_DEFAULT_IDLE_TIMEOUT);
        log.debug("FlowMod hard timeout set to {} seconds", 
                  FLOWMOD_DEFAULT_HARD_TIMEOUT);
    }
    
    private void deleteFlowOnSrcSwitch(Flow f){
    	NodePortTuple src = f.getSrc();
        OFFlowMod fm =
                (OFFlowMod) floodlightProvider.getOFMessageFactory()
                        .getMessage(OFType.FLOW_MOD);
        long cookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);

        fm.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
                .setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
                .setBufferId(OFPacketOut.BUFFER_ID_NONE)
                .setCookie(cookie)
                .setCommand(OFFlowMod.OFPFC_DELETE_STRICT)
                .setMatch(f.getMatch())
                .setLengthU(OFFlowMod.MINIMUM_LENGTH);

        fm.getMatch().setInputPort(src.getPortId());
        IOFSwitch sw = switches.get(src.getNodeId());
        log.warn("Remove flow on sw" + src.toString());
        try {
            sw.write(fm, null);
            sw.flush();
        } catch (IOException e) {
            log.error("Tried to write OFFlowMod to {} but failed: {}",
                    HexString.toHexString(sw.getId()), e.getMessage());
        }
    }

	@Override
	public synchronized void addPolicy(Policy p) {
		policyStorage.put(p.policyid, p);
        for (Flow f : flowStorage.values()) {
            boolean matchpolicy = false;
            for (OFMatch m : p.getRules()) {
                log.warn("Checking new policy: " + m.toString());
                if (flowBelongsToRule(f.getMatch(), m)) {
                    matchpolicy = true;
                    break;
                }
            }
            if (matchpolicy) {
				deleteFlowOnSrcSwitch(f);
            }
        }
	}

	@Override
	public synchronized void deletePolicy(Policy p) {
		Policy policyInStorage = policyStorage.get(p.policyid);
        if(policyInStorage == null) return;
		else {
			for(Flow f:policyInStorage.getFLows()){
				deleteFlowOnSrcSwitch(f);
                f.removeQueueForPolicy(p);
			}
			if(policyInStorage.getSwport()!=null){
	            queueCreaterService.deleteQueue(
	                    switches.get(policyInStorage.swport.getSwitchDPID()),
	                    (short) policyInStorage.swport.getPort(), policyInStorage.queue);
	            NodePortTuple queueport = new NodePortTuple(policyInStorage.getDpid(), policyInStorage.getPort());
	            if (swPolicyTuples.get(queueport) != null) {
	                swPolicyTuples.get(queueport).remove(p);
	            }
				
			}
            policyStorage.remove(policyInStorage.policyid);
        }
	}

	@Override
	public boolean checkIfPolicyExists(Policy policy) {
		if(policyStorage.containsKey(policy.policyid)){
			return true;
		}
		log.info("policyid: "+policy.policyid);
		for(OFMatch match:policy.getRules()){
        	log.info(match.toString());
        }
		return false;
	}

	@Override
	public List<Policy> getPolicies() {
		return (List<Policy>) policyStorage.values();
	}
}

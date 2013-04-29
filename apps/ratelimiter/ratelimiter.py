#! /usr/bin/python
"""
QoSPath.py v2---------------------------------------------------------------------------------------------------
Developed By: Ryan Wallner (ryan.wallner1@marist.edu)
Add QoS to a specific path in the network. Utilized circuit pusher developed by KC Wang
[Note]
    *the circuitpusher.py is needed in the same directory for this application to run
     succesfully! This circuitpusher instance is used WITHOUT pushing statis flows. 
     the static flows are commented out, circuitpusher is only used to get route.

    [author] - rjwallner
-----------------------------------------------------------------------------------------------------------------------
"""
import sys
import os
import re
import time
import json as simplejson #used to process policies and encode/decode requests
import subprocess #spawning subprocesses
import argparse

def main():
    parser = argparse.ArgumentParser(description="QoS Rate Limiter")
    parser.add_argument('-p','--port',
                    required=False,
                    default="8080",
                    type=str,
                    dest='p',
                    metavar="P")
    parser.add_argument('-c','--controller',
                    required=False,
                    default="127.0.0.1",
                    dest="c",
                    type=str,
                    metavar="C")
    parser.add_argument("-a","--add",
            required=False,
            dest="action_op",
            action="store_const",
            const="add",
            metavar="add")
    parser.add_argument("-d","--delete",
            required=False,
            dest="action_op",
            action="store_const",
            const="delete",
            metavar="delete")
    parser.add_argument("-s","--switch",
            required=False,
            dest="switch")
    parser.add_argument("-j","--json",
            required=True,
            dest="obj")
    parser.add_argument("-r","--rate",
            required=False,
            dest="rate")
    args = parser.parse_args()

    #initialize arguments
    c = args.c
    p = args.p
    action = args.action_op
    switch = args.switch
    json = args.obj
    rate = args.rate

    # Add/ Delete
    if action == "add":
        print "add"
        if json != None:
            #syntax check ip addresses
                #required fields
                #Credit: Sriram Santosh
            #ipPattern = re.compile('\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}')
            #if len(re.findall(ipPattern,src)) > 0:
            #        print "src good"
            #else:
            #        print "bad src"
            #        exit(1)
            #if len(re.findall(ipPattern,dest)) > 0:
            #        print "dest good"
            #else:
            #        print "bad dest"
            #        exit(1)
            #all goes well, add
            if rate != "":
                if os.getuid() != 0:
                    print "Root permissions required to add queue"
                    exit()
                j = simplejson.loads(json)
                queue_cmd = "sh ovs-set-port-queue.sh %s %s %s %s 0"%(switch,j['enqueue-port'],j['queue'],rate)
                print queue_cmd
                queue_res = os.popen(queue_cmd).read()
                print queue_res
                if queue_res.find("Success", 0) == -1:
                    exit(1)
            add(switch,json,c,p)
            exit()
        else:
            print "Missing arguments, check json"
            exit(1)
    elif action == "delete":
        print "delete"
        delete(switch,json,c,p)
        exit()
    else:
        print "action not unrecognized"
        exit(1)

#Add a Quality of Service Path
# @NAME  -Name of the Path
# @SRC   -Source IP
# @DEST  -Destination IP
# @JSON  -Json object of the policy
# @C, @P -Controller / Port
# 
# Author- Ryan Wallner    
def add(switch,json,c,p):
    qos_pusher = "qosmanager.py"
    pwd = os.getcwd()
    print pwd
    try:
        if (os.path.exists("%s/%s" % (pwd,qos_pusher))):
            print "Necessary tools confirmed.. %s" % (qos_pusher)
        else:
            print "%s/%s does not exist" %(pwd,qos_pusher)
    except ValueError as e:
        print "Problem finding tools... %s" % (qos_pusher)
        print e
        exit(1)
    
    j = simplejson.loads(json)
    #add necessary match values to policy for path
    j['name'] = "s"+switch+"p"+j['enqueue-port']+"q"+j['queue']
    #screwed up connectivity on this match, remove
    #p['ingress-port'] = str(in_prt)
    print "Adding Queueing Rule"
    sjson =  simplejson.JSONEncoder(sort_keys=False,indent=3).encode(j)
    print sjson
    cmd = "./qosmanager.py --add --type policy --json '%s' -c %s -p %s" % (sjson,c,p)
    res = subprocess.Popen(cmd, shell=True).wait()
                
def polErr():
    print """Your policy is not defined right, check to 
make sure you have a service OR a queue defined"""
    
#Delete a Quality of Service Path
# @NAME  -Name of the Path
# @C, @P -Controller / Port
# 
# Author- Ryan Wallner  
def delete(switch,json,c,p):
    j = simplejson.loads(json)
    #add necessary match values to policy for path
    j['name'] = "s"+switch+"p"+j['enqueue-port']+"q"+j['queue']
    #screwed up connectivity on this match, remove
    #p['ingress-port'] = str(in_prt)
    print "Deleting Queueing Rule"
    sjson =  simplejson.JSONEncoder(sort_keys=False,indent=3).encode(j)
    print sjson
    cmd = "./qosmanager.py --delete --type policy --json '%s' -c %s -p %s " % (sjson,c,p)
    print cmd
    subprocess.Popen(cmd,shell=True).wait() 

#Call main :)
if  __name__ == "__main__" :
    main()

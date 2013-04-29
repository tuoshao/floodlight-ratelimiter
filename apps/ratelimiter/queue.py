#! /usr/bin/python
#  coding: utf-8

'''
Add queues to Mininet using ovs-vsctl and ovs-ofctl
@Author Ryan Wallner
'''

import os
import sys
import time
import subprocess

if os.getuid() != 0:
    print "Root permissions required"
    exit()
        
max_queue = 10
controller = "127.0.0.1"
c_port = "6634"

def check_htb_exist(switch, port):
    check_cmd = "ovs-vsctl list QoS s%s-eth%s" % switch % port
    check_res = os.popen(check_cmd).read()
    if(check_res.find("no row", 0) >= 0):
        return False
    else:
        return True

def get_queue_num(switch, port):
    queue_cmd = "ovs-ofctl queue-stats tcp:%s:%s" % controller % c_port
    queue_res = os.popen(queue_cmd).read()
    queue_num_beg = queue_res.find(":",0)
    queue_num_end = queue_res.find("queues",queue_num_beg)
    queue_num = queue_res[queue_num_beg+2:queue_num_index-2]
    return int(queue_num)
    
def create_queue(switch, port,  max_rate, min_rate):
    queue_cmd = "sh ovs-set-port-queue.sh %d %d %d %d"
    queue_num = 1
    if(check_htb_exist(switch, port)):
        queue_num = get_queue_num(switch, port)
        if(int(queue_num) > max_queue):
            return -1
    else:
        queue_cmd = "ovs-vsctl -- set Port s1-eth1 qos=@newqos -- --id=@newqos create Qos type=linux-htb other-config:max-rate=1000000000 queues=0=@default -- --id=@default create Queue other-config:min-rate=1"
        os.popen(queue_cmd)
    queue_cmd = "ovs-vsctl -- add Qos s%s-eth%s queues %d=@q%d -- --id=@q%d create Queue other-config:max-rate=%d,min-rate=%d"%switch %port %queue_num %queue_num %queue_num %max_rate %min_rate
    os.popen(queue_cmd)
    return queue_num

def delete_queue(switch, port, queue_id):
    os.popen("./ovs-delete-port_queue.sh %d %d %d")%switch %port %queue_id
    
def find_all(a_str, sub_str):
    start = 0
    b_starts = []
    while True:
        start = a_str.find(sub_str, start)
        if start == -1: return b_starts
        #print start
        b_starts.append(start)
        start += 1



cmd = "ovs-vsctl show"
p = os.popen(cmd).read()
#print p

brdgs = find_all(p, "Bridge")
print brdgs

switches = []
for bn in brdgs:
        sw =  p[(bn+8):(bn+10)]
        switches.append(sw)

ports = find_all(p,"Port")
print ports

prts = []
for prt in ports:
        prt = p[(prt+6):(prt+13)]
        if '"' not in prt:
                print prt
                prts.append(prt)
config_strings = {}
for i in range(len(switches)):
        str = ""
        sw = switches[i]
        for n in range(len(prts)):
                #verify correct order
                if switches[i] in prts[n]:
                        #print switches[i]
                        #print prts[n]
                        port_name = prts[n]
                        str = str+" -- set port %s qos=@defaultqos" % port_name
        config_strings[sw] = str

#build queues per sw
print config_strings
for sw in switches:
        queuecmd = "sudo ovs-vsctl %s -- --id=@defaultqos create qos type=linux-htb other-config:max-rate=1000000000 queues=0=@q0,1=@q1,2=@q2 -- --id=@q0 create queue other-config:min-rate=1000000000 other-config:max-rate=1000000000 -- --id=@q1 create queue other-config:max-rate=20000000 -- --id=@q2 create queue other-config:max-rate=2000000 other-config:min-rate=2000000" % config_strings[sw]
        q_res = os.popen(queuecmd).read()
        print q_res





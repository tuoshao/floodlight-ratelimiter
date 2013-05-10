from mininet.net import Mininet
from mininet.topo import Topo
from mininet.topolib import TreeTopo
from ripl.dctopo import FatTreeTopo
from time import sleep
from mininet.node import RemoteController
from mininet.node import UserSwitch

import subprocess

ip = '172.16.29.1' 
port = 6633
httpport = 8080

class Rect( Topo ):
    def __init__( self ):
        "Create custom topo."

        # Initialize topology
        Topo.__init__( self )

        # Add hosts and switches
        h1 = self.addHost( 'h1' )
        h2 = self.addHost( 'h2' )
        h3 = self.addHost( 'h3' )
        h4 = self.addHost( 'h4' )
        s1 = self.addSwitch( 's1' )
        s2 = self.addSwitch( 's2' )
        s3 = self.addSwitch( 's3' )
        s4 = self.addSwitch( 's4' )

        # Add links
        self.addLink( s1, s2 )
        self.addLink( s2, s3 )
        self.addLink( s3, s4 )
        self.addLink( s4, s1 )
        self.addLink( s1, h1 )
        self.addLink( s2, h2 )
        self.addLink( s3, h3 )
        self.addLink( s4, h4 )
        
def iperf_server_tcp(host):
    host.popen('iperf -s -i 0.5 -y c -x c > test/%s_iperf_server_tcp.csv'%(host.name), shell = True)
    
def iperf_server_udp(host):
    host.popen('iperf -s -i 0.5 -y c -x c -u > test/%s_iperf_server_tcp.csv'%(host.name), shell = True)
    
def iperf_client_tcp(srchost, dsthost, duration, windowsize):
    srchost.popen('iperf -c %s -t %d -i 0.5 -y c -x c -w %s| cut -d \',\' -f1,8,9 > test/%s_to_%s_iperf_client.csv'%(dsthost.IP(), duration, windowsize, srchost.name, dsthost.name), shell = True)
    
def iperf_client_udp(srchost, dsthost, duration, bandwidth):
    srchost.popen('iperf -c %s -t %d -i 0.5 -y c -x c -b %s| cut -d \',\' -f1,8,9 > test/%s_to_%s_iperf_client.csv'%(dsthost.IP(), duration, windowsize, srchost.name, dsthost.name), shell = True)

def ping_client(srchost, dsthost, duration):
    srchost.popen('ping %s -w %d -D -i 0.5 | grep "from"| cut -d \' \' -f1,8 | cut -d \'[\' -f2 | tr -s "] time=" \',\' > test/%s_to_%s_ping.csv'%(dsthost.IP(), duration, srchost.name, dsthost.name), shell=True)

def add_policy(json):
    cmd = "./ratelimiter.py --add --json '%s' -c %s -p %s " % (json,ip,httpport)
    subprocess.Popen(cmd,shell=True)
def delete_policy(json):
    cmd = "./ratelimiter.py --delete --json '%s' -c %s -p %s " % (json,ip,httpport)
    subprocess.Popen(cmd,shell=True)
    
def general_rule_test():
    topo = FatTreeTopo()
    net = Mininet(topo=topo, switch=UserSwitch, controller=lambda name:RemoteController(name, ip=ip), listenPort=port)
    print len(net.hosts)
    net.start()
    print "mininet started" 
    sleep(15) 
    print "iperf test start"
    add_policy('{"rules":[{"ip-src":"10.0.0.2"}, {"ip-src":"10.0.0.3"}, {"ip-src":"10.2.1.2"}, {"ip-src":"10.3.1.3"}], "speed":30}')
    h1 = net.getNodeByName('0_0_2')
    h2 = net.getNodeByName('0_0_3')
    h3 = net.getNodeByName('0_1_2')
    h4 = net.getNodeByName('0_1_3')
    h5 = net.getNodeByName('1_0_2')
    h6 = net.getNodeByName('1_0_3')
    h7 = net.getNodeByName('2_1_2')
    h8 = net.getNodeByName('3_1_3')
    #setup all servers
    print "server start"
    iperf_server_tcp(h5)
    iperf_server_tcp(h6)
    iperf_server_tcp(h3)
    #iperf_server_tcp(h4)
    #setup all clients
    print "client start"
    sleep(10)
    iperf_client_tcp(h1, h5, 30, '2K')
    ping_client(h1, h5, 30)
    sleep(10)
    iperf_client_tcp(h2, h6, 20, '2K')
    ping_client(h2, h6, 20)
    sleep(10)
    iperf_client_tcp(h7, h3, 10, '2K')
    ping_client(h7, h3, 10)
    #iperf_client_tcp(h8, h4, 60, '2K')
    print "ping start"
    #ping_client(h8, h4, 60)
    #h1.cmd('iperf -c ', h3.IP(), ' -t 10 -i 1')
    #sleep(10)
    #add_policy('{"rules":[{"ip-src":"10.0.0.2"}, {"ip-src":"10.0.0.3"}, {"ip-src":"10.2.1.2"}, {"ip-src":"10.3.1.3"}], "speed":50}')
    #sleep(10)
    #add_policy('{"rules":[{"ip-src":"10.0.0.2"}, {"ip-src":"10.0.0.3"}], "speed":10}')
    #sleep(10)
    #delete_policy('{"rules":[{"ip-src":"10.0.0.2"}, {"ip-src":"10.0.0.3"}], "speed":10}')
    #sleep(10)
    #delete_policy('{"rules":[{"ip-src":"10.0.0.2"}, {"ip-src":"10.0.0.3"}, {"ip-src":"10.2.1.2"}, {"ip-src":"10.3.1.3"}], "speed":50}')
    sleep(20)
    print "iperf test end"
    net.stop()
    
def simulation_test():
    topo = FatTreeTopo()
    net = Mininet(topo=topo, switch=UserSwitch, controller=lambda name:RemoteController(name, ip=ip), listenPort=port)
    print len(net.hosts)
    net.start()
    print "mininet started" 
    sleep(15) 
    print "iperf test start"
    h1 = net.getNodeByName('0_0_2')
    h2 = net.getNodeByName('0_0_3')
    h3 = net.getNodeByName('0_1_2')
    h4 = net.getNodeByName('0_1_3')
    h5 = net.getNodeByName('1_0_2')
    h6 = net.getNodeByName('1_0_3')
    h7 = net.getNodeByName('2_1_2')
    h8 = net.getNodeByName('3_1_3')
    #setup all servers
    print "server start"
    iperf_server_tcp(h5)
    iperf_server_tcp(h6)
    iperf_server_tcp(h3)
    iperf_server_tcp(h4)
    #setup all clients
    print "client start"
    iperf_client_tcp(h1, h5, 60, '2K')
    iperf_client_tcp(h2, h6, 60, '2K')
    iperf_client_tcp(h7, h3, 60, '2K')
    iperf_client_tcp(h8, h4, 60, '2K')
    print "ping start"
    ping_client(h1, h5, 60)
    ping_client(h2, h6, 60)
    ping_client(h7, h3, 60)
    ping_client(h8, h4, 60)
    #h1.cmd('iperf -c ', h3.IP(), ' -t 10 -i 1')
    sleep(10)
    add_policy('{"rules":[{"ip-src":"10.0.0.2"}, {"ip-src":"10.0.0.3"}, {"ip-src":"10.2.1.2"}, {"ip-src":"10.3.1.3"}], "speed":50}')
    sleep(10)
    add_policy('{"rules":[{"ip-src":"10.0.0.2"}, {"ip-src":"10.0.0.3"}], "speed":10}')
    sleep(10)
    delete_policy('{"rules":[{"ip-src":"10.0.0.2"}, {"ip-src":"10.0.0.3"}], "speed":10}')
    sleep(10)
    delete_policy('{"rules":[{"ip-src":"10.0.0.2"}, {"ip-src":"10.0.0.3"}, {"ip-src":"10.2.1.2"}, {"ip-src":"10.3.1.3"}], "speed":50}')
    sleep(20)
    print "iperf test end"
    net.stop()
    
def small_test():
    topo = Rect()
    net = Mininet(topo=topo, switch=UserSwitch, controller=lambda name:RemoteController(name, ip=ip), listenPort=port)
    print len(net.hosts)
    net.start()
    print "mininet started" 
    sleep(15) 
    print "iperf test start"
    h1 = net.getNodeByName('h1')
    h2 = net.getNodeByName('h2')
    h3 = net.getNodeByName('h3')
    #setup all servers
    print "server start"
    iperf_server(h2)
    #h3.cmd('iperf -s');
    #setup all clients
    print "client start"
    iperf_client(h1, h2, 10)
    print "ping start"
    ping_client(h1, h2, 10)
    #h1.cmd('iperf -c ', h3.IP(), ' -t 10 -i 1')
    sleep(3)
    add_policy('{"rules":[{"ip-dst":"10.0.0.2"}], "speed":1}')
    sleep(3)
    delete_policy('{"rules":[{"ip-dst":"10.0.0.2"}], "speed":1}')
    sleep(7)
    print "iperf test end"
    net.stop()
    
if __name__ == '__main__':
    #general_rule_test()
    simulation_test()
    
#tree = FatTreeTopo(k=4)
#topo = TreeTopo(depth=2, fanout=4)
#topo = Rect
#net = Mininet(tree)
#print net.hosts[0].IP()
#net.start()
#test()
#h1, h4  = net.hosts[0], net.hosts[3]
#print h1.cmd('ping -c1 %s' % h4.IP())
#net.stop()
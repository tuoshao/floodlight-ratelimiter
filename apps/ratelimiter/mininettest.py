from mininet.net import Mininet
from mininet.topo import Topo
from mininet.topolib import TreeTopo
#from ripl.dctopo import FatTreeTopo
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
        
def iperf_server(host):
    #host.sendCmd('iperf -s -i 0.5 -y c -x c > test/' + host.name + '_iperf_server.csv')
    #output = open('test/'+host.name+'_iperf_server.csv', 'w+')
    #p1 = host.popen(['iperf', '-s', '-i', '0.5', '-y', 'c', '-x', 'c'])
    #p1.communicate()
    #host.pexec('iperf -s -i 0.5 -y c -x c > test/' + host.name + '_iperf_server.csv')
    #host.popen('iperf -s -t %d -i 0.5 -y c -x c | cut -d \',\' -f1,8,9 > test/%s_iperf_server.csv'%(duration, host.name), shell = True)
    host.popen('iperf -s -i 0.5 -y c -x c > test/%s_iperf_server.csv'%(host.name), shell = True)
    #host.popen('iperf -s -t %d -i 0.5 -y c -x c > test/%s_iperf_server.csv'%(duration, host.name), shell = True)
    
def iperf_client(srchost, dsthost, duration):
    #srchost.sendCmd('iperf -c ' , dsthost.IP() , ' -t ' , duration , ' -i 0.5 -y c -x c > test/' 
    #                + srchost.name + '_to_' + dsthost.name + '_iperf_client.csv')
    #srchost.pexec('iperf -c ' + dsthost.IP() + ' -t ' + str(duration) + ' -i 0.5 -y c -x c | cut -d \',\' -f1,8,9> test/' 
    #                + srchost.name + '_to_' + dsthost.name + '_iperf_client.csv')
    
    #output = open('test/'+srchost.name+'_to_'+dsthost.name+'_iperf_client.csv', 'w+')
    #p1 = srchost.popen(['iperf', '-c', dsthost.IP(), '-t', '10', '-i', '0.5', '-y', 'c', '-x', 'c'])
    srchost.popen('iperf -c %s -t %d -i 0.5 -y c -x c | cut -d \',\' -f1,8,9 > test/%s_to_%s_iperf_client.csv'%(dsthost.IP(), duration, srchost.name, dsthost.name), shell = True)
    #p1.communicate()
    return

def ping_client(srchost, dsthost, duration):
    #srchost.sendCmd('ping ' , dsthost.IP() , ' -t ' , duration , 
    #                ' -D | grep "from" | cut -d ' ' -f1,8 | cut -d \'[\' -f2 | tr -s "] time=" \',\' > test/'
    #                + srchost.name + '_to_' + dsthost.name + '_ping.csv')
    #srchost.sendCmd('ping ' , dsthost.IP() , ' -t ' , duration, ' > test/1.csv')  
    #srchost.pexec('ping ' + dsthost.IP() + ' -t ' + str(duration) + 
    #                ' -D | grep "from" | cut -d ' ' -f1,8 | cut -d \'[\' -f2 | tr -s "] time=" \',\' > test/'
    #                + srchost.name + '_to_' , dsthost.name + '_ping.csv')
    srchost.popen('ping %s -w %d -D | grep "from"| cut -d \' \' -f1,8 | cut -d \'[\' -f2 | tr -s "] time=" \',\' > test/%s_to_%s_ping.csv'%(dsthost.IP(), duration, srchost.name, dsthost.name), shell=True)
    #srchost.popen('ping %s -t %d -D > test/%s_to_%s_ping.csv'%(dsthost.IP(), duration, srchost.name, dsthost.name), shell=True)
    return

def add_policy(json):
    cmd = "./ratelimiter.py --add --json '%s' -c %s -p %s " % (json,ip,httpport)
    subprocess.Popen(cmd,shell=True)
def delete_policy(json):
    cmd = "./ratelimiter.py --delete --json '%s' -c %s -p %s " % (json,ip,httpport)
    subprocess.Popen(cmd,shell=True)
def test():
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
    test()
    
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
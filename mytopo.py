from mininet.topo import Topo

class Rect( Topo ):
    def __init__( self ):
        "Create custom topo."

        # Initialize topology
        Topo.__init__( self )

        # Add hosts and switches
        h1 = self.add_host( 'h1' )
        h2 = self.add_host( 'h2' )
        h3 = self.add_host( 'h3' )
        h4 = self.add_host( 'h4' )
        s1 = self.add_switch( 's1' )
        s2 = self.add_switch( 's2' )
        s3 = self.add_switch( 's3' )
        s4 = self.add_switch( 's4' )

        # Add links
        self.add_link( s1, s2 )
        self.add_link( s2, s3 )
        self.add_link( s3, s4 )
        self.add_link( s4, s1 )
        self.add_link( s1, h1 )
        self.add_link( s2, h2 )
        self.add_link( s3, h3 )
        self.add_link( s4, h4 )


topos = { 'rect': ( lambda: Rect() ) }
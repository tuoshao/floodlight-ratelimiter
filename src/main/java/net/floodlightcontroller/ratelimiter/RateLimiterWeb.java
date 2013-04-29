package net.floodlightcontroller.ratelimiter;

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

import net.floodlightcontroller.restserver.RestletRoutable;

public class RateLimiterWeb implements RestletRoutable {

	@Override
	public Restlet getRestlet(Context context) {
		Router router = new Router(context);
        router.attach("/policy/json", PolicyResource.class);
        return router;
	}

	@Override
	public String basePath() {
		return "/wm/ratelimiter";
	}

}

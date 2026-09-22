package dev.chaya.api.navigation;

import dev.chaya.api.navigation.NavigationDtos.RouteRequest;
import dev.chaya.api.navigation.NavigationDtos.RouteResponse;
import dev.chaya.api.security.ActorAuthentication;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/navigation")
public class NavigationController {

    private final RouteService routes;

    public NavigationController(RouteService routes) {
        this.routes = routes;
    }

    @PostMapping("/routes")
    @PreAuthorize("hasAnyRole('ADMIN','VENUE_MANAGER','OPERATOR','VIEWER','PUBLIC_VIEWER')")
    public RouteResponse route(@Valid @RequestBody RouteRequest request) {
        return routes.route(ActorAuthentication.currentActor(), request);
    }
}

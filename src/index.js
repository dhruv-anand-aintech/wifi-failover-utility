const FAILOVER_SECRET = "yZ0NDAKbwd24B9A4hjJxw2PTO+onteuBbe8RvWmqajo=";
const STATE_TTL = 600;  // 10 minutes

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;

    // Health check
    if (path === "/health") {
      return new Response("OK", { status: 200 });
    }

    // Command endpoints
    if (path === "/api/command/enable" && request.method === "POST") {
      return handleCommand(request, env, "enable");
    }

    if (path === "/api/command/disable" && request.method === "POST") {
      return handleCommand(request, env, "disable");
    }

    // Daemon heartbeat
    if (path === "/api/heartbeat" && request.method === "POST") {
      return handleHeartbeat(request, env);
    }

    // Status check
    if (path === "/api/status" && request.method === "GET") {
      return handleStatus(env);
    }

    // Acknowledge
    if (path === "/api/acknowledge" && request.method === "POST") {
      return handleAcknowledge(request, env);
    }

    return new Response("Not Found", { status: 404 });
  },

  async scheduled(event, env, ctx) {
    // Cleanup old states
    const keys = await env.WIFI_FAILOVER.list();
    const now = Date.now();

    for (const key of keys.keys) {
      const metadata = key.metadata || {};
      if (metadata.created && now - metadata.created > STATE_TTL * 1000) {
        await env.WIFI_FAILOVER.delete(key.name);
      }
    }
  }
};

async function handleCommand(request, env, action) {
  try {
    const body = await request.json();

    // Validate secret
    if (body.secret !== FAILOVER_SECRET) {
      return new Response(
        JSON.stringify({ error: "Invalid secret" }),
        { status: 403, headers: { "Content-Type": "application/json" } }
      );
    }

    // Store command in KV
    const state = {
      hotspot_enabled: action === "enable",
      timestamp: Date.now(),
      mac_acknowledged: false
    };

    await env.WIFI_FAILOVER.put("command", JSON.stringify(state), {
      expirationTtl: 600,  // Expire after 10 minutes
      metadata: { created: Date.now() }
    });

    return new Response(
      JSON.stringify({
        success: true,
        action: action,
        message: `Hotspot ${action} command stored`
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  } catch (error) {
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { "Content-Type": "application/json" } }
    );
  }
}

async function handleHeartbeat(request, env) {
  try {
    let body;
    try {
      body = await request.json();
    } catch (parseError) {
      return new Response(
        JSON.stringify({ error: "Invalid JSON: " + parseError.message }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      );
    }

    // Validate secret
    if (!body.secret || body.secret !== FAILOVER_SECRET) {
      return new Response(
        JSON.stringify({ error: "Invalid secret" }),
        { status: 403, headers: { "Content-Type": "application/json" } }
      );
    }

    // Store daemon heartbeat with status (active or paused)
    const heartbeat = {
      timestamp: Date.now(),
      daemon_alive: true,
      status: body.status || "active"  // active or paused
    };

    try {
      await env.WIFI_FAILOVER.put("daemon_heartbeat", JSON.stringify(heartbeat), {
        expirationTtl: 600,  // Expire after 10 minutes
        metadata: { created: Date.now() }
      });
    } catch (kvError) {
      return new Response(
        JSON.stringify({ error: "KV storage error: " + kvError.message }),
        { status: 500, headers: { "Content-Type": "application/json" } }
      );
    }

    return new Response(
      JSON.stringify({
        success: true,
        message: "Heartbeat received",
        timestamp: heartbeat.timestamp,
        status: heartbeat.status
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  } catch (error) {
    return new Response(
      JSON.stringify({ error: "Unknown error: " + error.message }),
      { status: 500, headers: { "Content-Type": "application/json" } }
    );
  }
}

async function handleStatus(env) {
  try {
    // Check daemon heartbeat
    const heartbeatStr = await env.WIFI_FAILOVER.get("daemon_heartbeat");
    const heartbeat = heartbeatStr ? JSON.parse(heartbeatStr) : {};

    const HEARTBEAT_TIMEOUT = 15000; // 15 seconds
    const now = Date.now();
    const lastHeartbeat = heartbeat.timestamp || 0;
    const timeSinceHeartbeat = now - lastHeartbeat;

    // Determine daemon status
    let daemon_status = "offline";
    if (timeSinceHeartbeat < HEARTBEAT_TIMEOUT) {
      // Daemon is responsive
      daemon_status = heartbeat.status === "paused" ? "paused" : "online";
    }

    const daemon_online = daemon_status === "online";

    const commandStr = await env.WIFI_FAILOVER.get("command");
    const command = commandStr ? JSON.parse(commandStr) : {};

    return new Response(
      JSON.stringify({
        daemon_status: daemon_status,  // "online", "paused", or "offline"
        daemon_online: daemon_online,  // true only if "online"
        daemon_last_heartbeat: lastHeartbeat,
        time_since_heartbeat: timeSinceHeartbeat,
        hotspot_enabled: command.hotspot_enabled || false,
        timestamp: command.timestamp || null,
        mac_acknowledged: command.mac_acknowledged || false
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  } catch (error) {
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { "Content-Type": "application/json" } }
    );
  }
}

async function handleAcknowledge(request, env) {
  try {
    let body;
    const contentType = request.headers.get("content-type") || "";

    if (contentType.includes("application/json")) {
      body = await request.json();
    } else {
      body = Object.fromEntries(await request.formData());
    }

    // Validate secret
    if (body.secret !== FAILOVER_SECRET) {
      return new Response(
        JSON.stringify({ error: "Invalid secret" }),
        { status: 403, headers: { "Content-Type": "application/json" } }
      );
    }

    // Update acknowledgment
    const commandStr = await env.WIFI_FAILOVER.get("command");
    if (commandStr) {
      const command = JSON.parse(commandStr);
      command.mac_acknowledged = true;
      command.acknowledged_at = Date.now();
      await env.WIFI_FAILOVER.put("command", JSON.stringify(command), {
        expirationTtl: 600,
        metadata: { created: Date.now() }
      });
    }

    return new Response(
      JSON.stringify({ success: true, message: "Acknowledged" }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  } catch (error) {
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { "Content-Type": "application/json" } }
    );
  }
}

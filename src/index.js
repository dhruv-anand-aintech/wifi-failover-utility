const FAILOVER_SECRET = "FAILOVER_SECRET_PLACEHOLDER";
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

async function handleStatus(env) {
  try {
    const commandStr = await env.WIFI_FAILOVER.get("command");
    const command = commandStr ? JSON.parse(commandStr) : {};

    return new Response(
      JSON.stringify({
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
    const body = request.formData
      ? Object.fromEntries(await request.formData())
      : await request.json();

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

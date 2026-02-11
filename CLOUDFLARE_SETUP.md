# Cloudflare Worker Setup

This guide walks you through deploying the Cloudflare Worker that coordinates between your Mac and Android phone.

## Prerequisites

- Cloudflare account (free tier works)
- Wrangler CLI installed
- `npm` or `yarn`

## Installation Steps

### 1. Install Wrangler

```bash
npm install -g wrangler
# or
yarn global add wrangler
```

### 2. Authenticate with Cloudflare

```bash
wrangler login
```

This opens your browser to authorize Wrangler.

### 3. Create KV Namespaces

KV storage holds the failover state temporarily.

```bash
# Create production namespace
wrangler kv:namespace create "WIFI_FAILOVER"

# Create preview namespace (for testing)
wrangler kv:namespace create "WIFI_FAILOVER" --preview
```

You'll get output like:
```
⛅ wrangler 2.x.x
🌀 Creating namespace with title "WIFI_FAILOVER"
✨ Success!
Add the following to your wrangler.toml:

kv_namespaces = [
 { binding = "WIFI_FAILOVER", id = "abc123...", preview_id = "def456..." }
]
```

Copy the `kv_namespaces` section - you'll need it for the next step.

### 4. Create `wrangler.toml`

Create a file named `wrangler.toml` in your project root:

```toml
name = "wifi-failover"
main = "src/index.js"
compatibility_date = "2024-01-01"

[env.production]
routes = [
  { pattern = "wifi-failover.YOUR-DOMAIN.workers.dev", zone_name = "YOUR-DOMAIN.com" }
]

kv_namespaces = [
  { binding = "WIFI_FAILOVER", id = "abc123...", preview_id = "def456..." }
]

[triggers]
crons = ["0 */10 * * * *"]  # Cleanup job every 10 minutes
```

**Important:** Replace the namespace IDs from Step 3.

### 5. Create the Worker Script

Create `src/index.js` with the following code:

```javascript
const FAILOVER_SECRET = "your-random-secret-here";  // Generate a random string
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
    const body = await request.formData
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
```

### 6. Generate Secret

Generate a random secret string:

```bash
# macOS/Linux
openssl rand -base64 32

# Or use Node
node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"
```

Copy this to the `FAILOVER_SECRET` in your Worker code.

### 7. Deploy

```bash
wrangler deploy
```

You'll get output like:
```
✨ Successfully published your Worker to
https://wifi-failover.youraccount.workers.dev
```

Save this URL - you'll need it for the setup wizard.

### 8. Test the Worker

```bash
# Test health endpoint
curl https://wifi-failover.youraccount.workers.dev/health

# Test status (should return hotspot_enabled: false)
curl https://wifi-failover.youraccount.workers.dev/api/status

# Trigger hotspot (replace SECRET)
curl -X POST https://wifi-failover.youraccount.workers.dev/api/command/enable \
  -H "Content-Type: application/json" \
  -d '{"secret": "YOUR-SECRET-HERE"}'

# Check status again
curl https://wifi-failover.youraccount.workers.dev/api/status
```

## Updating the Worker

If you need to modify the Worker code:

```bash
# Edit src/index.js
nano src/index.js

# Redeploy
wrangler deploy
```

## Troubleshooting

### "KV namespace not found"

Make sure the KV namespace IDs in `wrangler.toml` match what you created in Step 3.

### "Authentication failed"

Run `wrangler login` again to reauthenticate.

### Worker returns 404

Verify the endpoint path matches exactly. Check the Worker logs:

```bash
wrangler logs
```

### Secret doesn't match

Make sure you're using the exact secret string from `FAILOVER_SECRET` in your Worker code.

## Cost

Cloudflare Workers free tier includes:
- 100,000 requests/day
- 10ms CPU time/request (after first 50ms)
- 1GB KV storage

This setup easily fits in the free tier.

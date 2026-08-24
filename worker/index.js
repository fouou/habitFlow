// HabitFlow AI Insights Worker
// Proxies the app's OpenAI-compatible request to DeepSeek, keeping the REAL
// DeepSeek key server-side.

const DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";

// Reads a binding that may be either a plain string var or a KV-style binding (.get()).
// If the binding is missing, returns undefined instead of throwing (avoids HTTP 500/1101).
async function readBinding(env, name) {
  const v = env[name];
  if (v != null && typeof v.get === "function") return await v.get();
  return v;
}

export default {
  async fetch(request, env) {
    // 1. Authenticate the app (Authorization: Bearer <WORKER_TOKEN>)
    const appToken = await readBinding(env, "TOKEN");
    const auth = request.headers.get("Authorization") || "";
    if (!appToken || auth !== "Bearer " + appToken) {
      return new Response("Unauthorized", { status: 401 });
    }

    // 2. Forward the request to DeepSeek using the real API key
    const APIkey = await readBinding(env, "DEEPSEEK_API_KEY");
    if (!APIkey) {
      return new Response("DeepSeek key not configured", { status: 500 });
    }

    try {
      const upstream = await fetch(DEEPSEEK_URL, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${APIkey}`,
        },
        body: await request.text(),
      });

      if (!upstream.ok) {
        return new Response("Failed to fetch data", { status: upstream.status });
      }

      const data = await upstream.json();
      return new Response(JSON.stringify(data), {
        headers: { "Content-Type": "application/json" },
      });
    } catch (e) {
      return new Response("Upstream error: " + e.message, { status: 502 });
    }
  },
};

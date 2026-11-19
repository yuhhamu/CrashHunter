interface Bindings {
  CRASHHUNTER: Fetcher;
}

const apps: Record<string, keyof Bindings> = {
  crashhunter: "CRASHHUNTER",
};

export default {
  async fetch(req: Request, env: Bindings): Promise<Response> {
    const url = new URL(req.url);
    const app = url.pathname.split("/")[1] ?? "";
    const binding = apps[app];
    if (!binding) return new Response(JSON.stringify({ error: "not found" }), { status: 404, headers: { "content-type": "application/json" } });
    return env[binding].fetch(req);
  },
} satisfies ExportedHandler<Bindings>;

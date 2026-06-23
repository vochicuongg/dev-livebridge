import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { SSEServerTransport } from "@modelcontextprotocol/sdk/server/sse.js";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import express from "express";

const app = express();

// Bản vá: Tự động chèn header bỏ qua trang chặn cho mọi yêu cầu gửi đến
app.use((req, res, next) => {
    res.setHeader("ngrok-skip-browser-warning", "true");
    res.setHeader("bypass-tunnel-reminder", "true");
    next();
});

const server = new McpServer({
    name: "secure-filesystem-server",
    version: "1.0.0"
});

// Chạy cổng 3000 dưới dạng endpoint /mcp chuẩn cho Langdock
app.get("/mcp", async (req, res) => {
    const transport = new SSEServerTransport("/mcp", res);
    await server.connect(transport);
});

app.listen(3000, () => console.log("MCP Server running on port 3000"));

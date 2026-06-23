import express from 'express';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { SSEServerTransport } from '@modelcontextprotocol/sdk/server/sse.js';
import { ListToolsRequestSchema, CallToolRequestSchema } from '@modelcontextprotocol/sdk/types.js';
import fs from 'fs';
import path from 'path';

const app = express();
app.use(express.json());

// Bản vá: Tự động xóa bỏ trang cảnh báo của mọi dịch vụ Tunnel (Ngrok/Localtunnel)
app.use((req, res, next) => {
    res.setHeader("ngrok-skip-browser-warning", "true");
    res.setHeader("bypass-tunnel-reminder", "true");
    next();
});

const mcpServer = new Server({ name: "secure-filesystem-server", version: "1.0.0" }, { capabilities: { tools: {} } });

// Khai báo công cụ list_directory cho Langdock quét tính năng
mcpServer.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: [{
        name: "list_directory",
        description: "List contents of the allowed directory",
        inputSchema: { type: "object", properties: { path: { type: "string" } }, required: ["path"] }
    }]
}));

// Xử lý logic đọc file khi Claude Opus 4.8 gọi lệnh
mcpServer.setRequestHandler(CallToolRequestSchema, async (request) => {
    if (request.params.name === "list_directory") {
        try {
            const files = fs.readdirSync("D:\\livebridge");
            return { content: [{ type: "text", text: `Các file trong D:\\livebridge:\n${files.join('\n')}` }] };
        } catch (err) {
            return { content: [{ type: "text", text: `Lỗi: ${err.message}` }], isError: true };
        }
    }
});

let transport;

// Endpoint xử lý yêu cầu kết nối SSE từ Langdock
app.get("/mcp", async (req, res) => {
    transport = new SSEServerTransport("/mcp-message", res);
    await mcpServer.connect(transport);
});

// Endpoint nhận thông điệp từ Langdock gửi về máy của bạn
app.post("/mcp-message", async (req, res) => {
    if (transport) {
        await transport.handleMessage(req, res);
    } else {
        res.status(400).send("Kết nối chưa được khởi tạo");
    }
});

// Bản vá mấu chốt: Tự động phản hồi danh sách công cụ nếu Langdock quét trực tiếp qua POST /mcp
app.post("/mcp", async (req, res) => {
    res.json({
        jsonrpc: "2.0",
        result: {
            tools: [{
                name: "list_directory",
                description: "List contents of the allowed directory",
                inputSchema: { type: "object", properties: { path: { type: "string" } }, required: ["path"] }
            }]
        },
        id: req.body.id || 1
    });
});

app.listen(3000, () => console.log("MCP HTTP Server is running perfectly on port 3000!"));
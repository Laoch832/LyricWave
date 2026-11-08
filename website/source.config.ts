import { defineDocs, defineConfig } from "fumadocs-mdx/config";
import { remarkAnchors } from "./src/lib/remark-anchors.mjs";
export const docs = defineDocs({ dir: "content/docs" });
export default defineConfig({ mdxOptions: { remarkPlugins: [remarkAnchors] } });

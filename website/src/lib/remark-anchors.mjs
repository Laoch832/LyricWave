import { visit } from "unist-util-visit";
/** Preserve stable repository anchors, which Markdown otherwise drops as raw HTML. */
export function remarkAnchors() {
  return (tree) => {
    visit(tree, "html", (node, index, parent) => {
      const match = node.value.match(/^<a id="([^"]+)">(?:<\/a>)?$/);
      if (match) {
        node.type =
          parent?.type === "paragraph"
            ? "mdxJsxTextElement"
            : "mdxJsxFlowElement";
        node.name = "span";
        node.attributes = [
          { type: "mdxJsxAttribute", name: "id", value: match[1] },
        ];
        node.children = [];
        delete node.value;
      } else if (node.value === "</a>") {
        node.type = "text";
        node.value = "";
      }
    });
  };
}

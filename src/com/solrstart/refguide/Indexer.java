package com.solrstart.refguide;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.ConcurrentUpdateSolrClient;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrInputDocument;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.ast.*;

import static org.asciidoctor.OptionsBuilder.options;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class Indexer {

    public static final String FIELD_CHILDREN = "children";
    private static TreeSet<String> CONTEXTS = new TreeSet<>();
    private static Asciidoctor asciidoctor;
    private static String fileName;
    private static Map<String, Object> options = options().asMap();
    private static int totalDocs = 0;

    public static void main(String[] args) throws IOException, SolrServerException {
        asciidoctor = Asciidoctor.Factory.create();

        ConcurrentUpdateSolrClient solrClient;
        solrClient = new ConcurrentUpdateSolrClient.Builder("http://localhost:8983/solr/refguide").build();
        solrClient.deleteByQuery("*:*");
        solrClient.commit();

        int totalFilesIndexed = 0;

        for (String path : args) {
            File fileOrDir = new File(path);
            if (fileOrDir.isDirectory()) {
                System.out.println("Indexing directory: " + fileOrDir.getAbsolutePath());
                for (File file : fileOrDir.listFiles((dir, name) -> name.endsWith(".adoc"))) {
                    System.out.println("    Indexing: " + file.getAbsolutePath());
                    indexFile(solrClient, file);
                    totalFilesIndexed++;
                }
            } else {
                System.out.println("Indexing: " + fileOrDir.getAbsolutePath());
                indexFile(solrClient, fileOrDir);
                totalFilesIndexed++;
            }
        }

        solrClient.commit();
        solrClient.close();

        System.out.printf("\n\nTotal files indexed: %d, including %d documents\n", totalFilesIndexed, totalDocs);
    }

    private static void indexFile(ConcurrentUpdateSolrClient solrClient, File file) throws IOException, SolrServerException {
        fileName = file.getName();
        fileName = fileName.substring(0, fileName.lastIndexOf('.')); //strip extension
        Document document = asciidoctor.loadFile(file, options);

        //Create root Solr document, into which everything else goes
        //This is mostly because "document" may or may not have text, depending on whether it detects a preamble
        //https://asciidoctor.org/docs/user-manual/#doc-preamble (no lvl1 section => no preamble either, just root text)

        SolrInputDocument rootDoc = createSolrInputDocumentWithChildren(null, fileName, fileName, document.getDoctitle());
        rootDoc.setField("isDocumentRoot", true);

        ArrayDeque<String> titles = new ArrayDeque<>();

        indexStructure(document, titles, rootDoc);
        setChildenCount(rootDoc);
        UpdateResponse add = solrClient.add(rootDoc);
    }

    private static void indexStructure(StructuralNode parentNode, ArrayDeque<String> titles, SolrInputDocument parentSolrDoc)
            throws IOException, SolrServerException {
        String id = null;
        String anchor = null;
        String context = parentNode.getContext();

        //We don't want to go one level deeper after this element
        boolean isDocEntry = false;

        switch (context) {
            case "document":
                isDocEntry = true;
                id = makeID("##DOC");
                Document doc = (Document) parentNode;
                titles.addLast(doc.getDoctitle());
                break;
            case "preamble":
                id = makeID("##PREAMBLE");
                titles.addLast("Preamble");
                break;
            case "section":
                Section section = (Section) parentNode;
                titles.addLast(section.getTitle());
                anchor = denormaliseAnchor(section.getId());
                break;
            default:
                System.err.println("Unknown parent node: " + context);
        }

        if (id == null) {
            id = makeID(anchor);
        }

        System.out.println("Looking at: "  + String.join(" >> ", titles));

        SolrInputDocument doc = createSolrInputDocumentWithChildren(parentSolrDoc, id, fileName, titles.getLast());
        if (anchor != null) {
            doc.addField("anchor", anchor);
        }
        doc.addField("path", titles.toArray());
        doc.addField("level", parentNode.getLevel());

        List<String> extractedText = indexChildren(parentNode.getBlocks(), titles, isDocEntry?parentSolrDoc:doc);
        if (extractedText == null || extractedText.size() == 0) {
            doc.addField("hasText", false);
        } else {
            doc.addField("hasText", true);
            doc.addField("text", extractedText);
        }

        setChildenCount(doc);

        titles.removeLast();
    }

    private static String makeID(String anchor) {
        return fileName + ':' + anchor;
    }

    private static List<String> indexChildren(List<StructuralNode> nodes, ArrayDeque<String> titles, SolrInputDocument parentSolrDoc)
            throws IOException, SolrServerException {
        if (nodes == null || nodes.isEmpty()) { return null; }

        List<String> children = new ArrayList<>();

        for (StructuralNode node : nodes) {
            String context = node.getContext();
            switch (context) {
                case "section":
                case "preamble":
                    indexStructure(node, titles, parentSolrDoc);
                    break;
                case "paragraph":
                case "listing":
                case "admonition":
                case "image":
                case "quote":
                case "open":
                case "sidebar":
                case "literal":
                case "example":
                case "pass":
                case "thematic_break":
                    Block block = (Block) node;
                    int contentLinesCount = indexContentNode(children, block);
                    if (contentLinesCount == 0) {
                        switch (block.getContentModel()) {
                            case "empty":
                                break;
                            case "compound":
                                List<String> nestedChildren = indexChildren(block.getBlocks(), titles, parentSolrDoc);
                                children.addAll(nestedChildren);
                                break;
                            default:
                                System.err.println("UNRECOGNIZED CONTENT MODEL WITH EMPTY TEXT: " + context);
                                break;
                        }
                    }
                    break;

                case "dlist":
                    DescriptionList dlist = (DescriptionList) node;
                    if (dlist.hasItems()) {
                        for (DescriptionListEntry entry : dlist.getItems()) {
                            for (ListItem term : entry.getTerms()) {
                                children.add(term.getSource());
                            }
                            ListItem desc = entry.getDescription();
                            String content = desc.getSource();
                            if (content != null) {
                                children.add(content);
                            } else {
                                List<String> nestedChildren = indexChildren(desc.getBlocks(), titles, parentSolrDoc);
                                children.addAll(nestedChildren);
                            }
                        }

                    }
                    break;
                case "ulist":
                case "olist":
                case "colist":
                    org.asciidoctor.ast.List list = (org.asciidoctor.ast.List) node;
                    for (StructuralNode listItemNode : list.getItems()) {
                        ListItem listItem = (ListItem) listItemNode;
                        children.add(listItem.getSource());
                    }
                    break;

                case "table":
                    Table table = (Table) node;
                    extractCells(table.getHeader(), children);
                    extractCells(table.getBody(), children);
                    extractCells(table.getFooter(), children);
                    break;
                default:
                    System.err.printf("UNKNOWN CHILD NODE: %s (%s)\n---\n%s\n---\n",
                            context, node.getClass().getSimpleName(), node.convert());
                    children.add(node.convert());
            }
        }
        return children;
    }

    /**
     * Index captions, titles and text lines (if any of 3 exist)
     * @return number of text lines indexed (zero is special case)
     */
    private static int indexContentNode(List<String> children, Block block) {
        String blockCaption = block.getCaption();
        if (blockCaption != null) {
            children.add(blockCaption);
        }
        String blockTitle = block.getTitle();
        if (blockTitle != null) {
            children.add(blockTitle);
        }

        List<String> lines = block.getLines();
        children.addAll(lines);
        return lines.size();
    }

    private static SolrInputDocument createSolrInputDocumentWithChildren(SolrInputDocument parent, String id, String fileName, String title) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", id);
        doc.setField("fileName", fileName);
        doc.setField("title", title);

        doc.setField(FIELD_CHILDREN, new ArrayList<SolrInputDocument>());

        if (parent != null) {
            parent.getField(FIELD_CHILDREN).addValue(doc);
        }

        totalDocs++;
        return doc;
    }

    private static void setChildenCount(SolrInputDocument doc) {
        doc.setField("childrenCount", doc.getFieldValues(FIELD_CHILDREN).size());
    }

    /**
     Somehow, asciidoc converts #foo-bar-bla into _foo_bar_bla
     and there does not seem an easy way to get it back.
     So, we are just going to reverse-hack it
     **/
    private static String denormaliseAnchor(String anchor) {
        if (anchor.charAt(0) != '_') { return anchor; } // oops

        return "#" + anchor.substring(1).replace('_', '-');
    }

    private static void extractCells(List<Row> rows, List<String> children) {
        if (rows == null) { return; }

        for (Row row: rows) {
            for (Cell cell : row.getCells()) {
                children.add(cell.getSource());
            }
        }
    }


    private static String OFFSET = "                                                         ";

    private static boolean printStructure(List<StructuralNode> nodes, int offsetLevel) {
        if (nodes == null) return false;

        for (StructuralNode node : nodes) {
            CONTEXTS.add(node.getContext()); //just for statistical purposes

            int level = node.getLevel();
//            String currentOffset = OFFSET.substring(0, (level * 4) + (isChild?2:0));
            String currentOffset = OFFSET.substring(0, offsetLevel * 4);
            System.out.printf("%s%d: %s (Style: %s, ContentModel: %s, Context: %s, Class: %s)\n",
                    currentOffset, level,
                    node.getId(), node.getStyle(), node.getContentModel(), node.getContext(),
                    node.getClass().getSimpleName()
                    );
            if (node instanceof Block) {
                List<String> lines = ((Block) node).getLines();
                int linesCount = lines.size();
                if (linesCount > 0) {
                    System.out.printf("%s  (1 of %d): %s\n", currentOffset, linesCount, lines.get(0));
                }
            }
//            System.out.printf("%svvvv\n%s\n%1$s^^^^\n", currentOffset, block.getContent());
            printStructure(node.getBlocks(), offsetLevel+1);
        }
        return true;
    }
}

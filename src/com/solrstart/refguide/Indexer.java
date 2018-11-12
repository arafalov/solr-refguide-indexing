package com.solrstart.refguide;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.ConcurrentUpdateSolrClient;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.ast.*;

import static org.asciidoctor.OptionsBuilder.options;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class Indexer {

    private static TreeSet<String> CONTEXTS = new TreeSet<>();
    private static ConcurrentUpdateSolrClient solrClient;
    private static String fileName;

    public static void main(String[] args) throws IOException, SolrServerException {
        File testFile = new File(args[0]);

        fileName = testFile.getName();
        System.out.println("Testing with: " + fileName);

        Asciidoctor asciidoctor = Asciidoctor.Factory.create();
        Map<String, Object> options = options().asMap();

        Document document = asciidoctor.loadFile(testFile, options);

        System.out.println(document.getDoctitle());

        solrClient = new ConcurrentUpdateSolrClient.Builder("http://localhost:8983/solr/refguide").build();
        solrClient.deleteByQuery("*:*");
        solrClient.commit();

        ArrayDeque<String> titles = new ArrayDeque<>();

        indexStructure(document, titles);

//        printStructure(document.getBlocks(), 0);


        System.out.println("\n\n\nCONTEXTS:");
        System.out.println(CONTEXTS);
        solrClient.commit();
        solrClient.close();
    }

    private static void indexStructure(StructuralNode parentNode, ArrayDeque<String> titles) throws IOException, SolrServerException {
        String anchor = null;
        String context = parentNode.getContext();
        switch (context) {
            case "document":
                Document doc = (Document) parentNode;
                titles.addLast(doc.getDoctitle());
                break;
            case "preamble":
                titles.addLast("Preamble");
                break;
            case "section":
                Section section = (Section) parentNode;
                titles.addLast(section.getTitle());
                anchor = section.getId();
                break;
            default:
                System.err.println("Unknown parent node: " + context);
        }

        System.out.println("Looking at: "  + String.join(" >> ", titles));

        List<String> children = indexChildren(parentNode.getBlocks(), titles);

        if (children != null) {
            SolrInputDocument doc = new SolrInputDocument();
            doc.addField("id", fileName + ':' + anchor);
            if (anchor != null) { doc.addField("anchor", anchor); }
            doc.addField("path", String.join(" >> ", titles));
            doc.addField("title", titles.getLast()); //for better matching
            doc.addField("level", parentNode.getLevel());
            doc.addField("text", children);
            UpdateResponse add = solrClient.add(doc);

        }

        titles.removeLast();


//        System.out.printf("%s%d: %s (Style: %s, ContentModel: %s, Context: %s, Class: %s)\n",
//                "", parentNode.getLevel(),
//                parentNode.getId(), parentNode.getStyle(), parentNode.getContentModel(), parentNode.getContext(),
//                parentNode.getClass().getSimpleName()
//        );
    }

    private static List<String> indexChildren(List<StructuralNode> nodes, ArrayDeque<String> titles) throws IOException, SolrServerException {
        if (nodes == null || nodes.isEmpty()) { return null; }

        List<String> children = new ArrayList<>();

        for (StructuralNode node : nodes) {
            String context = node.getContext();
            switch (context) {
                case "section":
                case "preamble":
                    indexStructure(node, titles);
                    break;
                case "paragraph":
                case "listing":
                case "admonition":
                    Block block = (Block) node;
                    children.addAll(block.getLines());
                    break;
                case "dlist":
                    DescriptionList dlist = (DescriptionList) node;
                    if (dlist.hasItems()) {
                        for (DescriptionListEntry entry : dlist.getItems()) {
                            for (ListItem term : entry.getTerms()) {
                                children.add(term.getSource());
                            }
                            children.add(entry.getDescription().getSource());
                        }

                    }
                    break;
                case "ulist":
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
                    System.err.println("UNKNOWN CHILD NODE: " + context);
                    children.add(node.convert());
            }
        }
//        //stub
//        children.add("Content 1");
//        children.add("Content 2");

        // process various child types
        // if child is a section, recurse

        return children;
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

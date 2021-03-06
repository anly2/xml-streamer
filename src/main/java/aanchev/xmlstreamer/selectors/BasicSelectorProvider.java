package aanchev.xmlstreamer.selectors;

import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import aanchev.parser.SimpleParser;
import aanchev.parser.SimpleParser.AST;
import aanchev.parser.SimpleParser.Castable;
import aanchev.xmlstreamer.ChildCulling;
import aanchev.xmlstreamer.Element;
import aanchev.xmlstreamer.ReactiveXMLStreamer;

public class BasicSelectorProvider extends AbstractSelectorProvider {
	/*
	public static void main(String[] args) {
		String input = "#main .title ~ ul > li:not(a) + [href] ~ a#target.link[href^=\"https\"]";
		Selector root = new Selector.Compiler(null).compile(input);
		System.out.println(root);
	}
	//*/


	/* Constructors */

	public BasicSelectorProvider(ReactiveXMLStreamer notifier) {
		super(notifier);
	}


	/* ParserDecorator Contract */

	@Override
	public void decorate(SimpleParser.Builder builder) {
		builder
			.rule("\\s*+"+ "([^\\s~]++)"   +"\\s*+"+   "~"    +"\\s*+" +"(.*+)", m -> selSibling())
			.rule("\\s*+"+ "([^\\s\\+]++)" +"\\s*+"+  "\\+"   +"\\s*+" +"(.*+)", m -> selImmediateSibling())
			.rule("\\s*+"+ "([^\\s>]++)"   +"\\s*+"+   ">"    +"\\s*+" +"(.*+)", m -> selImmediateDescendent())
			.rule("\\s*+"+ "(\\S++)"+                "\\s++"           +"(.++)", m -> selDescendent())
			.rule("\\s*+"+ "([^\\[\\]]++)?" + "\\[([^\\s\\]]++)\\]" +"\\s*+", m -> selAttribute(m.group(2)), 2)
			.rule("\\s*+"+ "([^\\s#]++)?"   + "\\#([\\w\\-]++)"     +"\\s*+", m -> selAttrId(m.group(2)), 2)
			.rule("\\s*+"+ "([^\\s\\.]++)?" + "\\.([\\w\\-]++)"     +"\\s*+", m -> selAttrClass(m.group(2)), 2)
			.rule("\\s*+"+                    "([a-zA-Z]\\w*+)"     +"\\s*+", m -> selTag(m.group()), 1)
			.rule("\\s*+"+ "([^\\s:]++)?"   + ":before" +"\\s*+", m -> selTraitBefore())
			.rule("\\s*+"+ "([^\\s:]++)?"   + ":after"  +"\\s*+", m -> selTraitAfter())
			.rule("\\s*+"+ "([^\\s:]++)?"   + ":not\\(([^\\)]++)\\)", m -> selTraitNot())
			.rule("\\*", m -> selAny());
	}


	/*** Selector.Factory ***/

	/* Selector types */

	protected abstract class SimpleSelectorNode extends SelectorNode {
		public SimpleSelectorNode(String raw) {
			super(raw, 1);
		}

		@Override
		public void attach() {
			Selector inner;

			if (getChild(0) == null) {
				AST e = selAny();
				setChild(0, e);
				inner = e.cast();
			}
			else
				inner = getChild(0).cast();

			inner.triggers(element -> {
				if (matches(element))
					action.accept(element);
			});
			inner.attach();
		}

		@Override
		public void detach() {
			getChild(0).<Selector>cast().detach();
		}


		protected abstract boolean matches(Element element);


		@Override
		public String getSelector() {
			return any(getChild(0)) + super.getSelector();
		}
	}


	/* Helper methods */

	private static String any(Object o) {
		return o == null? "*" : o.toString();
	}

	private static Selector sellink(Castable selector, Consumer<Element> action) {
		Selector sel = selector.cast();
		sel.attach();
		sel.triggers(action);
		return sel;
	}


	/* Selector instance creators */

	public AST selAny() {
		return new SelectorLeaf("*") {
			@Override
			public void accept(Element element) {
				action.accept(element);
			}
		};
	}

	public AST selTag(String tag) {
		return new SelectorLeaf(tag) {
			@Override
			public void accept(Element element) {
				if (element.getTag().equalsIgnoreCase(tag))
					action.accept(element);
			}
		};
	}


	public AST selAttribute(String attr) {
		return new SimpleSelectorNode("["+attr+"]") {
			@Override
			protected boolean matches(Element element) {
				return (element.getAttribute(attr) != null);
			}
		};
	}

	public AST selAttrId(String id) {
		return new SimpleSelectorNode("#"+id) {
			@Override
			protected boolean matches(Element element) {
				final Object v = element.getAttribute("id");
				return (v != null && id.equals(v));
			}
		};
	}

	public AST selAttrClass(String cls) {
		return new SimpleSelectorNode("."+cls) {
			private Predicate<String> pred = Pattern.compile("(?:^|\\s)\\Q"+cls+"\\E(?:$|\\S)").asPredicate();
			@Override
			protected boolean matches(Element element) {
				final Object v = element.getAttribute("class");
				return (v != null && pred.test(v.toString()));
			}
		};
	}


	public AST selDescendent() {
		return new BinarySelectorNode("	") {
			private int depth = -1;
			private Consumer<Element> startTracker;
			private Consumer<Element> endTracker;

			@Override
			public void attach() {
				//## order of attachments matters! -- trackers first

				startTracker = notifier.onTagStart(e -> {
					if (depth >= 0)
						depth++;
				});

				endTracker = notifier.onTagEnd(e -> {
					if (depth >= 0)
						depth--;
				});


				// attach PARENT selector
				sellink(getChild(0), e -> {
					if (depth < 0)
						depth = 0;
				});

				// attach ELEMENT selector
				sellink(getChild(1), e -> {
					if (depth > 0)
						action.accept(e);
				});
			}

			@Override
			public void detach() {
				notifier.offTagStart(startTracker);
				notifier.offTagEnd(endTracker);

				startTracker = null;
				endTracker = null;

				getChild(0).<Selector>cast().detach();
				getChild(1).<Selector>cast().detach();
			}
		};
	}

	public AST selImmediateDescendent() {
		return new BinarySelectorNode(">") {
			private int depth = -1;
			private Consumer<Element> startTracker;
			private Consumer<Element> endTracker;

			@Override
			public void attach() {
				//## order of attachments matters! -- trackers first

				startTracker = notifier.onTagStart(e -> {
					if (depth >= 0)
						depth++;
				});

				endTracker = notifier.onTagEnd(e -> {
					if (depth >= 0)
						depth--;
				});


				// attach PARENT selector
				sellink(getChild(0), e -> {
					depth = 0;
				});

				// attach ELEMENT selector
				sellink(getChild(1), e -> {
					if (depth == 1)
						action.accept(e);
				});
			}

			@Override
			public void detach() {
				notifier.offTagStart(startTracker);
				notifier.offTagEnd(endTracker);

				startTracker = null;
				endTracker = null;

				getChild(0).<Selector>cast().detach();
				getChild(1).<Selector>cast().detach();
			}
		};
	}


	public AST selSibling() {
		return new BinarySelectorNode("~") {
			private int depth = -1;
			private Consumer<Element> startTracker;
			private Consumer<Element> endTracker;
			private Deque<Integer> occurances = new LinkedList<>();

			@Override
			public void attach() {
				//## order of attachments matters!

				// attach ELEMENT selector first!
				sellink(getChild(1), e -> {
					if (depth >= 0 && occurances.peek() == depth)
						action.accept(e);
				});

				// attach SIBLING selector second!
				sellink(getChild(0), e -> {
					if (depth < 0)
						depth = 0;

					if (occurances.isEmpty() || occurances.peek() < depth)
						occurances.push(depth);
				});


				// attach trackers
				startTracker = notifier.onTagStart(e -> {
					if (depth >= 0)
						depth++;
				});

				endTracker = notifier.onTagEnd(e -> {
					if (depth >= 0)
						depth--;

					if (!occurances.isEmpty() && occurances.peek() > depth)
						occurances.pop();
				});
			}

			@Override
			public void detach() {
				notifier.offTagStart(startTracker);
				notifier.offTagEnd(endTracker);

				startTracker = null;
				endTracker = null;

				getChild(0).<Selector>cast().detach();
				getChild(1).<Selector>cast().detach();
			}
		};
	}

	public AST selImmediateSibling() {
		return new BinarySelectorNode("+") {
			private boolean active = false;
			private boolean wasActive = false; //keep until tag-end for possible checks then
			private Consumer<Element> endTracker;
			private Consumer<Element> startTracker;
			private Set<Element> pending = new HashSet<>();

			@Override
			public void attach() {
				// attach ELEMENT selector
				sellink(getChild(1), e -> {
					if ((!e.isClosed() && active) || (e.isClosed() && wasActive))
						action.accept(e);
				});

				// attach SIBLING selector
				sellink(getChild(0), e -> {
					pending.add(e);
				});


				// attach the trackers
				startTracker = notifier.onTagStart(e -> {
					wasActive = active;
					active = false;
				});

				endTracker = notifier.onTagEnd(e -> {
					active = pending.remove(e);
				});
			}

			@Override
			public void detach() {
				notifier.offTagStart(startTracker);
				notifier.offTagEnd(endTracker);

				startTracker = null;
				endTracker = null;

				getChild(0).<Selector>cast().detach();
				getChild(1).<Selector>cast().detach();
			}
		};
	}


	public AST selTraitBefore() {
		return new SimpleSelectorNode(":before") {
			// selAny() triggers on tag-start already

			@Override
			protected boolean matches(Element element) {
				return !element.isClosed();
			}
		};
	}

	public AST selTraitAfter() {
		return new SimpleSelectorNode(":after") {
			private Consumer<Element> endTracker;
			private Set<Element> pending = new HashSet<>();

			@Override
			protected boolean matches(Element element) {
				if (element.isClosed())
					return true;

				if (notifier instanceof ChildCulling)
					((ChildCulling) notifier).keepChildren(true); //keep the subsequent children

				pending.add(element);
				return false;
			}

			@Override
			public void attach() {
				super.attach();

				endTracker = notifier.onTagEnd(e -> {
					if (pending.remove(e))
						action.accept(e);
				});
			}

			@Override
			public void detach() {
				super.detach();

				notifier.offTagEnd(endTracker);
				endTracker = null;
			}
		};
	}


	public AST selTraitNot() {
		return new SelectorNode(":not()", 2) {
			private Element withTrait = null;

			@Override
			public void attach() {
				AST selElement = getChild(0);

				if (selElement == null) {
					selElement = selAny();
					setChild(0, selElement);
				}


				//## order of attachments matter!

				sellink(getChild(1), e -> {
					withTrait = e;
				});

				sellink(selElement, e -> {
					if (withTrait == null) //if not with trait
						action.accept(e);

					withTrait = null; //reset to be ready for the next
				});
			}

			@Override
			public void detach() {
				withTrait = null;
				getChild(0).<Selector>cast().detach();
				getChild(1).<Selector>cast().detach();
			}

			@Override
			public String getSelector() {
				return any(getChild(0)) + ":not(" + getChild(1) + ")";
			}
		};
	}
}
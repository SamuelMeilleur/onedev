package io.onedev.server.plugin.report.jest;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.form.AjaxFormComponentUpdatingBehavior;
import org.apache.wicket.ajax.markup.html.form.AjaxButton;
import org.apache.wicket.ajax.markup.html.form.AjaxCheckBox;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.extensions.markup.html.repeater.data.grid.ICellPopulator;
import org.apache.wicket.extensions.markup.html.repeater.data.table.AbstractColumn;
import org.apache.wicket.extensions.markup.html.repeater.data.table.DataTable;
import org.apache.wicket.extensions.markup.html.repeater.data.table.IColumn;
import org.apache.wicket.extensions.markup.html.repeater.data.table.NavigationToolbar;
import org.apache.wicket.extensions.markup.html.repeater.util.SortableDataProvider;
import org.apache.wicket.feedback.FencedFeedbackPanel;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.link.Link;
import org.apache.wicket.markup.html.navigation.paging.PagingNavigator;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.apache.wicket.util.string.StringValue;
import org.eclipse.jgit.lib.FileMode;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.onedev.commons.codeassist.InputSuggestion;
import io.onedev.commons.codeassist.parser.TerminalExpect;
import io.onedev.server.git.BlobIdent;
import io.onedev.server.model.Build;
import io.onedev.server.plugin.report.jest.JestTestReportData.Status;
import io.onedev.server.plugin.report.jest.JestTestReportData.TestSuite;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.util.DateUtils;
import io.onedev.server.util.patternset.PatternSet;
import io.onedev.server.web.WebConstants;
import io.onedev.server.web.ajaxlistener.ConfirmLeaveListener;
import io.onedev.server.web.behavior.PatternSetAssistBehavior;
import io.onedev.server.web.component.chart.pie.PieChartPanel;
import io.onedev.server.web.component.chart.pie.PieSlice;
import io.onedev.server.web.component.link.ViewStateAwarePageLink;
import io.onedev.server.web.component.pagenavigator.HistoryAwarePagingNavigator;
import io.onedev.server.web.page.project.blob.ProjectBlobPage;
import io.onedev.server.web.util.LoadableDetachableDataProvider;
import io.onedev.server.web.util.SuggestionUtils;

@SuppressWarnings("serial")
public class JestTestSuitesPage extends JestTestReportPage {

	public static final String PARAM_FILE = "file";
	
	public static final String PARAM_LONGEST_DURATION_FIRST = "longestDurationFirst";
	
	public static final String PARAM_STATUS = "status";
	
	private State state = new State();
	
	private Optional<PatternSet> filePatterns;
	
	private final IModel<List<TestSuite>> testSuitesModel = new LoadableDetachableModel<List<TestSuite>>() {

		@Override
		protected List<TestSuite> load() {
			if (filePatterns != null) {
				List<TestSuite> testSuites = getReportData().getTestSuites(filePatterns.orNull(), state.statuses);
				if (state.longestDurationFirst) {
					testSuites.sort(new Comparator<TestSuite>() {

						@Override
						public int compare(TestSuite o1, TestSuite o2) {
							return (int)(o2.getDuration()/1000 - o1.getDuration()/1000);
						}
						
					});
				}
				return testSuites;
			} else {
				return new ArrayList<>();
			}
		}
		
	};
	
	private Form<?> form;

	private Component feedback;
	
	private Component summary;

	private Component orderBy;
	
	private DataTable<TestSuite, Void> detail;
	
	public JestTestSuitesPage(PageParameters params) {
		super(params);
		
		state.file = params.get(PARAM_FILE).toOptionalString();
		state.longestDurationFirst = params.get(PARAM_LONGEST_DURATION_FIRST).toBoolean(false);
		state.statuses = new LinkedHashSet<>();

		if (!"none".equals(params.get(PARAM_STATUS).toString())) {
			for (StringValue each: params.getValues(PARAM_STATUS)) 
				state.statuses.add(Status.valueOf(each.toString().toUpperCase()));
			
			if (state.statuses.isEmpty()) {
				state.statuses.add(Status.PASSED);
				state.statuses.add(Status.FAILED);
			}
		}
	}
	
	private void pushState(AjaxRequestTarget target) {
		CharSequence url = urlFor(JestTestSuitesPage.class, paramsOf(getBuild(), getReportName(), state));
		pushState(target, url.toString(), state);
	}

	@Override
	protected void onPopState(AjaxRequestTarget target, Serializable data) {
		super.onPopState(target, data);
		state = (State) data;
		parseFilePatterns();
		target.add(form);
		target.add(summary);
		target.add(orderBy);
		target.add(detail);
	}

	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		if (getReportData() != null) {
			Fragment fragment = new Fragment("testSuites", "hasDataFrag", this);
			
			form = new Form<Void>("form");
			TextField<String> fileFilter = new TextField<String>("file", new IModel<String>() {

				@Override
				public void detach() {
				}

				@Override
				public String getObject() {
					return state.file;
				}

				@Override
				public void setObject(String object) {
					state.file = object;
				}
				
			});
			fileFilter.add(new PatternSetAssistBehavior() {
				
				@Override
				protected List<InputSuggestion> suggest(String matchWith) {
					return SuggestionUtils.suggest(
							getReportData().getTestSuites().stream().map(it->it.getName()).collect(Collectors.toList()), 
							matchWith);
				}
				
				@Override
				protected List<String> getHints(TerminalExpect terminalExpect) {
					return Lists.newArrayList(
							"Path containing spaces or starting with dash needs to be quoted",
							"Use '**', '*' or '?' for <a href='$docRoot/pages/path-wildcard.md' target='_blank'>path wildcard match</a>. Prefix with '-' to exclude"
							);
				}
				
			});
			form.add(fileFilter);
			
			fileFilter.add(new AjaxFormComponentUpdatingBehavior("clear") {
				
				@Override
				protected void onUpdate(AjaxRequestTarget target) {
					pushState(target);
					parseFilePatterns();
					target.add(feedback);
					target.add(summary);
					target.add(detail);
				}
				
			});
			
			form.add(feedback = new FencedFeedbackPanel("feedback", form));
			feedback.setOutputMarkupPlaceholderTag(true);

			form.add(new AjaxButton("submit") {

				@Override
				protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
					super.updateAjaxAttributes(attributes);
					attributes.getAjaxCallListeners().add(new ConfirmLeaveListener(this));
				}
				
				@Override
				protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
					super.onSubmit(target, form);
					pushState(target);
					parseFilePatterns();
					target.add(feedback);
					target.add(summary);
					target.add(detail);
				}
				
			});
			
			fragment.add(form);
			
			parseFilePatterns();
			
			fragment.add(summary = new PieChartPanel("summary", new LoadableDetachableModel<List<PieSlice>>() {

				@Override
				protected List<PieSlice> load() {
					if (filePatterns != null) {
						List<PieSlice> slices = new ArrayList<>();
						for (Status status: Status.values()) {
							int numOfTestSuites = getReportData().getTestSuites(
									filePatterns.orNull(), Sets.newHashSet(status)).size();
							slices.add(new PieSlice(status.name().toLowerCase(), numOfTestSuites, 
									status.getColor(), state.statuses.contains(status)));
						}
						return slices;
					} else {
						return null;
					}
				}
				
			}) {

				@Override
				protected void onSelectionChange(AjaxRequestTarget target, String sliceName) {
					Status status = Status.valueOf(sliceName.toUpperCase());
					if (state.statuses.contains(status))
						state.statuses.remove(status);
					else
						state.statuses.add(status);
					pushState(target);
					target.add(detail);
				}
				
			});
			
			fragment.add(orderBy = new AjaxCheckBox("longestDurationFirst", new IModel<Boolean>() {

				@Override
				public void detach() {
				}

				@Override
				public Boolean getObject() {
					return state.longestDurationFirst;
				}

				@Override
				public void setObject(Boolean object) {
					state.longestDurationFirst = object;
				}
				
			}) {
				
				@Override
				protected void onUpdate(AjaxRequestTarget target) {
					pushState(target);
					target.add(detail);
				}
				
			});
			
			List<IColumn<TestSuite, Void>> columns = new ArrayList<>();
			
			columns.add(new AbstractColumn<TestSuite, Void>(Model.of("")) {

				@Override
				public void populateItem(Item<ICellPopulator<TestSuite>> cellItem, 
						String componentId, IModel<TestSuite> rowModel) {
					TestSuite testSuite = rowModel.getObject();
					Fragment fragment = new Fragment(componentId, "testSuiteFrag", JestTestSuitesPage.this);
					
					fragment.add(new TestStatusBadge("status", testSuite.getStatus()));
					
					JestTestCasesPage.State state = new JestTestCasesPage.State();
					state.file = testSuite.getName();
					state.statuses = JestTestSuitesPage.this.state.statuses;
					PageParameters params = JestTestCasesPage.paramsOf(getBuild(), getReportName(), state);
					Link<Void> link = new ViewStateAwarePageLink<Void>("testCases", 
							JestTestCasesPage.class, params);
					link.add(new Label("label", testSuite.getName()));
					
					BlobIdent blobIdent = new BlobIdent(getBuild().getCommitHash(), testSuite.getName(), 
							FileMode.REGULAR_FILE.getBits());
					if (SecurityUtils.canReadCode(getProject()) && getProject().getBlob(blobIdent, false) != null) {
						fragment.add(new ViewStateAwarePageLink<Void>("viewSource", ProjectBlobPage.class, 
								ProjectBlobPage.paramsOf(getProject(), blobIdent)));
					} else {
						fragment.add(new WebMarkupContainer("viewSource").setVisible(false));
					}
										
					fragment.add(new Label("duration", DateUtils.formatDuration(testSuite.getDuration())));
					fragment.add(link);
					
					fragment.add(new TestMessagePanel("message", testSuite.getMessage()) {

						@Override
						protected Build getBuild() {
							return JestTestSuitesPage.this.getBuild();
						}
						
					}.setVisible(testSuite.getMessage() != null));
					
					cellItem.add(fragment);
				}

			});
			
			SortableDataProvider<TestSuite, Void> dataProvider = new LoadableDetachableDataProvider<TestSuite, Void>() {

				@Override
				public Iterator<? extends TestSuite> iterator(long first, long count) {
					if (getTestSuites().size() > first+count)
						return getTestSuites().subList((int)first, (int)(first+count)).iterator();
					else
						return getTestSuites().subList((int)first, getTestSuites().size()).iterator();
				}

				@Override
				public long calcSize() {
					return getTestSuites().size();
				}

				@Override
				public IModel<TestSuite> model(TestSuite object) {
					return Model.of(object);
				}
				
			};			
			fragment.add(detail = new DataTable<TestSuite, Void>("detail", columns, 
					dataProvider, WebConstants.PAGE_SIZE));
			
			detail.addBottomToolbar(new NavigationToolbar(detail) {

				@Override
				protected PagingNavigator newPagingNavigator(String navigatorId, DataTable<?, ?> table) {
					return new HistoryAwarePagingNavigator(navigatorId, table, null);
				}
				
			});
			
			fragment.add(detail.setOutputMarkupId(true));
			
			add(fragment);
		} else {
			add(new Label("testSuites", "No test suites published")
					.add(AttributeAppender.append("class", "alert alert-notice alert-light-warning")));
		}
		
	}
	
	private void parseFilePatterns() {
		if (state.file != null) {
			try {
				filePatterns = Optional.of(PatternSet.parse(state.file));
			} catch (Exception e) {
				filePatterns = null;
				form.error("Malformed file filter");
			}
		} else {
			filePatterns = Optional.absent();
		}
	}
	
	private List<TestSuite> getTestSuites() {
		return testSuitesModel.getObject();
	}

	@Override
	protected void onDetach() {
		testSuitesModel.detach();
		super.onDetach();
	}

	public static PageParameters paramsOf(Build build, String reportName, State state) {
		PageParameters params = paramsOf(build, reportName);
		if (state.file != null)
			params.add(PARAM_FILE, state.file);
		if (state.longestDurationFirst)
			params.add(PARAM_LONGEST_DURATION_FIRST, state.longestDurationFirst);
		if (state.statuses != null) {
			if (!state.statuses.isEmpty()) {
				for (Status status: state.statuses)
					params.add(PARAM_STATUS, status.name().toLowerCase());
			} else {
				params.add(PARAM_STATUS, "none");
			}
		}
		return params;
	}
	
	public static class State implements Serializable {
		
		@Nullable
		public String file;
		
		public boolean longestDurationFirst;
		
		public Collection<Status> statuses;
		
	}
	
}

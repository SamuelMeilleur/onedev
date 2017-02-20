package com.gitplex.server.web.component.branchpicker;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.attributes.CallbackParameter;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.JavaScriptHeaderItem;
import org.apache.wicket.markup.head.OnDomReadyHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.request.IRequestParameters;
import org.apache.wicket.request.cycle.RequestCycle;

import com.gitplex.server.git.GitUtils;
import com.gitplex.server.git.RefInfo;
import com.gitplex.server.model.Depot;
import com.gitplex.server.web.behavior.AbstractPostAjaxBehavior;
import com.gitplex.server.web.behavior.InputChangeBehavior;
import com.gitplex.server.web.util.ajaxlistener.ConfirmLeaveListener;

@SuppressWarnings("serial")
public abstract class BranchSelector extends Panel {
	
	private final IModel<Depot> depotModel;
	
	private final String branch;
	
	private int activeBranchIndex;
	
	private String branchInput;
	
	private AbstractPostAjaxBehavior keyBehavior;
	
	private TextField<String> branchField;

	private final List<String> branches = new ArrayList<>();
	
	private final List<String> filteredBranches = new ArrayList<>();
	
	public BranchSelector(String id, IModel<Depot> depotModel, String branch) {
		super(id);
		
		this.depotModel = depotModel;
		this.branch = branch;		
		
		for (RefInfo ref: depotModel.getObject().getBranches())
			branches.add(GitUtils.ref2branch(ref.getRef().getName()));
		
		filteredBranches.addAll(branches);
	}

	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		branchField = new TextField<String>("branch", Model.of(""));
		branchField.setOutputMarkupId(true);
		add(branchField);
		
		keyBehavior = new AbstractPostAjaxBehavior() {
			
			@Override
			protected void respond(AjaxRequestTarget target) {
				IRequestParameters params = RequestCycle.get().getRequest().getPostParameters();
				String key = params.getParameterValue("key").toString();
				
				if (key.equals("return")) {
					if (!filteredBranches.isEmpty()) 
						onSelect(target, filteredBranches.get(activeBranchIndex));
				} else if (key.equals("up")) {
					activeBranchIndex--;
				} else if (key.equals("down")) {
					activeBranchIndex++;
				} else {
					throw new IllegalStateException("Unrecognized key: " + key);
				}
			}

			@Override
			public void renderHead(Component component, IHeaderResponse response) {
				super.renderHead(component, response);
				String script = String.format("gitplex.server.branchSelector.init('%s', %s);", 
						getMarkupId(true), getCallbackFunction(CallbackParameter.explicit("key")));
				response.render(OnDomReadyHeaderItem.forScript(script));
			}
			
		};
		add(keyBehavior);
		
		branchField.add(new InputChangeBehavior() {
			
			@Override
			protected void onInputChange(AjaxRequestTarget target) {
				branchInput = branchField.getInput();
				filteredBranches.clear();
				if (StringUtils.isNotBlank(branchInput)) {
					branchInput = branchInput.trim();
					for (String branch: branches) {
						if (branch.contains(branchInput))
							filteredBranches.add(branch);
					}
				} else {
					branchInput = null;
					filteredBranches.addAll(branches);
				}
				
				if (activeBranchIndex >= filteredBranches.size())
					activeBranchIndex = 0;
				target.add(get("branches"));
			}
			
		});
		
		WebMarkupContainer branchesContainer = new WebMarkupContainer("branches");
		branchesContainer.add(new ListView<String>("branches", filteredBranches) {

			@Override
			protected void populateItem(final ListItem<String> item) {
				AjaxLink<Void> link = new AjaxLink<Void>("link") {

					@Override
					protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
						super.updateAjaxAttributes(attributes);
						attributes.getAjaxCallListeners().add(new ConfirmLeaveListener());
					}
					
					@Override
					public void onClick(AjaxRequestTarget target) {
						onSelect(target, item.getModelObject());
					}

				};
				link.add(new Label("label", item.getModelObject()));
				if (item.getModelObject().equals(branch))
					link.add(AttributeAppender.append("class", " current"));
				item.add(link);
				
				if (activeBranchIndex == item.getIndex())
					item.add(AttributeAppender.append("class", " active"));
			}
			
		});
		branchesContainer.setOutputMarkupId(true);
		add(branchesContainer);
		
		setOutputMarkupId(true);
	}
	
	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(JavaScriptHeaderItem.forReference(new BranchSelectorResourceReference()));
	}

	protected abstract void onSelect(AjaxRequestTarget target, String branch);

	@Override
	protected void onDetach() {
		depotModel.detach();
		
		super.onDetach();
	}
	
}

package com.pmease.gitplex.core.manager.impl;

import static com.pmease.gitplex.core.model.PullRequest.CriterionHelper.ofOpen;
import static com.pmease.gitplex.core.model.PullRequest.CriterionHelper.ofSource;
import static com.pmease.gitplex.core.model.PullRequest.CriterionHelper.ofTarget;
import static com.pmease.gitplex.core.model.PullRequest.IntegrationStrategy.MERGE_ALWAYS;
import static com.pmease.gitplex.core.model.PullRequest.IntegrationStrategy.MERGE_IF_NECESSARY;
import static com.pmease.gitplex.core.model.PullRequest.IntegrationStrategy.MERGE_WITH_SQUASH;
import static com.pmease.gitplex.core.model.PullRequest.IntegrationStrategy.REBASE_SOURCE_ONTO_TARGET;
import static com.pmease.gitplex.core.model.PullRequest.IntegrationStrategy.REBASE_TARGET_ONTO_SOURCE;
import static com.pmease.gitplex.core.model.PullRequest.Status.PENDING_APPROVAL;
import static com.pmease.gitplex.core.model.PullRequest.Status.PENDING_INTEGRATE;
import static com.pmease.gitplex.core.model.PullRequest.Status.PENDING_UPDATE;

import java.io.File;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.Query;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Restrictions;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.pmease.commons.git.Git;
import com.pmease.commons.git.GitUtils;
import com.pmease.commons.git.command.MergeCommand.FastForwardMode;
import com.pmease.commons.hibernate.Sessional;
import com.pmease.commons.hibernate.Transactional;
import com.pmease.commons.hibernate.UnitOfWork;
import com.pmease.commons.hibernate.dao.Dao;
import com.pmease.commons.hibernate.dao.EntityCriteria;
import com.pmease.commons.markdown.MarkdownManager;
import com.pmease.commons.util.FileUtils;
import com.pmease.gitplex.core.MentionParser;
import com.pmease.gitplex.core.listeners.LifecycleListener;
import com.pmease.gitplex.core.listeners.PullRequestListener;
import com.pmease.gitplex.core.listeners.RepositoryListener;
import com.pmease.gitplex.core.manager.ConfigManager;
import com.pmease.gitplex.core.manager.PullRequestCommentManager;
import com.pmease.gitplex.core.manager.PullRequestManager;
import com.pmease.gitplex.core.manager.PullRequestNotificationManager;
import com.pmease.gitplex.core.manager.PullRequestUpdateManager;
import com.pmease.gitplex.core.manager.ReviewInvitationManager;
import com.pmease.gitplex.core.manager.StorageManager;
import com.pmease.gitplex.core.manager.UserManager;
import com.pmease.gitplex.core.model.Config;
import com.pmease.gitplex.core.model.IntegrationPolicy;
import com.pmease.gitplex.core.model.IntegrationPreview;
import com.pmease.gitplex.core.model.PullRequest;
import com.pmease.gitplex.core.model.PullRequest.CloseStatus;
import com.pmease.gitplex.core.model.PullRequest.IntegrationStrategy;
import com.pmease.gitplex.core.model.PullRequestActivity;
import com.pmease.gitplex.core.model.PullRequestComment;
import com.pmease.gitplex.core.model.PullRequestUpdate;
import com.pmease.gitplex.core.model.PullRequestVisit;
import com.pmease.gitplex.core.model.RepoAndBranch;
import com.pmease.gitplex.core.model.Repository;
import com.pmease.gitplex.core.model.ReviewInvitation;
import com.pmease.gitplex.core.model.User;

@Singleton
public class DefaultPullRequestManager implements PullRequestManager, RepositoryListener, LifecycleListener {

	private static final Logger logger = LoggerFactory.getLogger(DefaultPullRequestManager.class);
	
	private final Dao dao;
	
	private final ConfigManager configManager;
	
	private final PullRequestUpdateManager pullRequestUpdateManager;
	
	private final PullRequestCommentManager pullRequestCommentManager;
	
	private final UserManager userManager;
	
	private final MarkdownManager markdownManager;
	
	private final StorageManager storageManager;
	
	private final UnitOfWork unitOfWork;
	
	private final Set<PullRequestListener> pullRequestListeners;
	
	private final ReviewInvitationManager reviewInvitationManager;
	
	private final Set<Long> integrationPreviewCalculatingRequestIds = new ConcurrentHashSet<>();

	private ThreadPoolExecutor integrationPreviewExecutor;

	@SuppressWarnings("serial")
	private final BlockingDeque<Runnable> integrationPreviewQueue = new LinkedBlockingDeque<Runnable>() {

		@Override
		public boolean offer(Runnable e) {
			return super.offerFirst(e);
		}
		
	};

	@Inject
	public DefaultPullRequestManager(Dao dao, PullRequestUpdateManager pullRequestUpdateManager, 
			StorageManager storageManager, ReviewInvitationManager reviewInvitationManager, 
			UserManager userManager, PullRequestNotificationManager notificationManager, 
			ConfigManager configManager, PullRequestCommentManager pullRequestCommentManager, 
			MarkdownManager markdownManager, UnitOfWork unitOfWork, 
			Set<PullRequestListener> pullRequestListeners) {
		this.dao = dao;
		this.pullRequestUpdateManager = pullRequestUpdateManager;
		this.storageManager = storageManager;
		this.reviewInvitationManager = reviewInvitationManager;
		this.pullRequestCommentManager = pullRequestCommentManager;
		this.userManager = userManager;
		this.unitOfWork = unitOfWork;
		this.configManager = configManager;
		this.markdownManager = markdownManager;
		this.pullRequestListeners = pullRequestListeners;
	}

	@Transactional
	@Override
	public void delete(PullRequest request) {
		deleteRefs(request);
		
		dao.remove(request);
	}

	@Sessional
	@Override
	public void deleteRefs(PullRequest request) {
		for (PullRequestUpdate update : request.getUpdates())
			update.deleteRefs();
		
		request.deleteRefs();
	}

	@Transactional
	@Override
	public void restoreSourceBranch(PullRequest request) {
		Preconditions.checkState(!request.isOpen() && request.getSourceRepo() != null);

		if (request.getSource().getHead(false) == null) {
			request.getSourceRepo().git().createBranch(
					request.getSourceBranch(), 
					request.getLatestUpdate().getHeadCommitHash());
		}
	}

	@Transactional
	@Override
	public void reopen(PullRequest request, String comment) {
		Preconditions.checkState(!request.isOpen());
		
		request.setCloseStatus(null);
		dao.persist(request);
		
		User user = userManager.getCurrent();
		PullRequestActivity activity = new PullRequestActivity();
		activity.setRequest(request);
		activity.setDate(new DateTime().minusSeconds(1).toDate());
		activity.setAction(PullRequestActivity.Action.REOPEN);
		activity.setUser(user);
		
		dao.persist(activity);

		if (comment != null) {
			PullRequestComment requestComment = new PullRequestComment();
			requestComment.setContent(comment);
			requestComment.setDate(activity.getDate());
			requestComment.setRequest(request);
			requestComment.setUser(user);
			pullRequestCommentManager.save(requestComment, false);
		}

		onSourceBranchUpdate(request, false);
		
		if (request.isOpen()) {
			for (PullRequestListener listener: pullRequestListeners)
				listener.onReopened(request, user, comment);
		}
	}

	@Transactional
	@Override
 	public void discard(PullRequest request, final String comment) {
		User user = userManager.getCurrent();
		PullRequestActivity activity = new PullRequestActivity();
		activity.setRequest(request);
		activity.setDate(new Date());
		activity.setAction(PullRequestActivity.Action.DISCARD);
		activity.setUser(user);
		
		dao.persist(activity);

		if (comment != null) {
			PullRequestComment requestComment = new PullRequestComment();
			requestComment.setContent(comment);
			requestComment.setDate(activity.getDate());
			requestComment.setRequest(request);
			requestComment.setUser(user);
			
			pullRequestCommentManager.save(requestComment, false);
		}

		request.setCloseStatus(CloseStatus.DISCARDED);
		request.setLastEventDate(activity.getDate());
		dao.persist(request);
		
		for (PullRequestListener listener: pullRequestListeners)
			listener.onDiscarded(request, user, comment);
	}
	
	@Transactional
	@Override
	public void integrate(PullRequest request, String comment) {
		if (request.getStatus() != PENDING_INTEGRATE)
			throw new IllegalStateException("Gate keeper disallows integration right now.");
	
		IntegrationPreview preview = request.getIntegrationPreview();
		if (preview == null)
			throw new IllegalStateException("Integration preview has not been calculated yet.");

		String integrated = preview.getIntegrated();
		if (integrated == null)
			throw new IllegalStateException("There are integration conflicts.");
		
		User user = userManager.getCurrent();

		Repository targetRepo = request.getTargetRepo();
		Git git = targetRepo.git();
		IntegrationStrategy strategy = request.getIntegrationStrategy();
		if ((strategy == MERGE_ALWAYS || strategy == MERGE_IF_NECESSARY || strategy == MERGE_WITH_SQUASH) 
				&& !preview.getIntegrated().equals(preview.getRequestHead()) && comment != null) {
			File tempDir = FileUtils.createTempDir();
			try {
				Git tempGit = new Git(tempDir);
				tempGit.clone(git.repoDir().getAbsolutePath(), false, true, true, request.getTargetBranch());
				tempGit.updateRef("HEAD", preview.getIntegrated(), null, null);
				tempGit.reset(null, null);
				
				tempGit.commit(comment, false, true);
				integrated = tempGit.parseRevision("HEAD", true);
				git.fetch(tempGit, "+HEAD:" + request.getIntegrateRef());									
				comment = null;
			} finally {
				FileUtils.deleteDir(tempDir);
			}
		}
		if (strategy == REBASE_SOURCE_ONTO_TARGET || strategy == MERGE_WITH_SQUASH) {
			Repository sourceRepo = request.getSourceRepo();
			Git sourceGit = sourceRepo.git();
			String sourceRef = request.getSourceRef();
			sourceGit.updateRef(sourceRef, integrated, preview.getRequestHead(), 
					"Pull request #" + request.getId());
			sourceRepo.cacheObjectId(request.getSourceRef(), ObjectId.fromString(integrated));
			onRefUpdate(sourceRepo, sourceRef, integrated);
		}
		
		String targetRef = request.getTargetRef();
		git.updateRef(targetRef, integrated, preview.getTargetHead(), "Pull request #" + request.getId());
		targetRepo.cacheObjectId(request.getTargetRef(), ObjectId.fromString(integrated));
		onRefUpdate(targetRepo, targetRef, integrated);
		
		PullRequestActivity activity = new PullRequestActivity();
		activity.setRequest(request);
		activity.setDate(new Date());
		activity.setAction(PullRequestActivity.Action.INTEGRATE);
		activity.setUser(user);
		
		dao.persist(activity);

		if (comment != null) {
			PullRequestComment requestComment = new PullRequestComment();
			requestComment.setContent(comment);
			requestComment.setDate(activity.getDate());
			requestComment.setRequest(request);
			requestComment.setUser(user);
			pullRequestCommentManager.save(requestComment, false);
		}

		request.setCloseStatus(CloseStatus.INTEGRATED);
		request.setLastEventDate(new Date());

		dao.persist(request);

		for (PullRequestListener listener: pullRequestListeners)
			listener.onIntegrated(request, user, comment);
	}
	
	@Transactional
	@Override
	public void open(final PullRequest request, final Object listenerData) {
		dao.persist(request);

		FileUtils.cleanDir(storageManager.getCacheDir(request));
		
		request.git().updateRef(request.getBaseRef(), request.getBaseCommitHash(), null, null);
		
		for (PullRequestUpdate update: request.getUpdates()) {
			update.setDate(new Date(System.currentTimeMillis() + 1000));
			pullRequestUpdateManager.save(update, false);
		}
		
		for (ReviewInvitation invitation: request.getReviewInvitations())
			reviewInvitationManager.save(invitation);

		for (PullRequestListener listener: pullRequestListeners)
			listener.onOpened(request);
		
		if (request.getDescription() != null) {
			String rawHtml = markdownManager.parse(request.getDescription());
			Collection<User> mentions = new MentionParser().parseMentions(rawHtml);
			for (User user: mentions) {
				for (PullRequestListener listener: pullRequestListeners)
					listener.onMentioned(request, user);
			}
		}
		
		dao.afterCommit(new Runnable() {

			@Override
			public void run() {
				IntegrationPreviewTask task = new IntegrationPreviewTask(request.getId());
				integrationPreviewExecutor.remove(task);
				integrationPreviewExecutor.execute(task);
			}
			
		});
	}

	@Override
	public List<IntegrationStrategy> getApplicableIntegrationStrategies(PullRequest request) {
		List<IntegrationStrategy> strategies = null;
		for (IntegrationPolicy policy: request.getTargetRepo().getIntegrationPolicies()) {
			if (policy.getTargetBranches().matches(request.getTargetBranch()) 
					&& policy.getSourceBranches().matches(request.getSourceRepo(), request.getSourceBranch())) {
				strategies = policy.getIntegrationStrategies();
				break;
			}
		}
		if (strategies == null) 
			strategies = Lists.newArrayList(IntegrationStrategy.MERGE_ALWAYS);
		return strategies;
	}

	@Transactional
	@Override
	public void onAssigneeChange(PullRequest request) {
		dao.persist(request);
		for (PullRequestListener listener: pullRequestListeners)
			listener.onAssigned(request);
	}
	
	@Transactional
	@Override
	public void onTargetBranchUpdate(PullRequest request) {
		closeIfMerged(request);
		if (request.isOpen()) {
			IntegrationPreviewTask task = new IntegrationPreviewTask(request.getId());
			integrationPreviewExecutor.remove(task);
			integrationPreviewExecutor.execute(task);
		}
	}

	@Transactional
	private void closeIfMerged(PullRequest request) {
		Git git = request.getTargetRepo().git();
		if (git.isAncestor(request.getLatestUpdate().getHeadCommitHash(), request.getTarget().getHead())) {
			PullRequestActivity activity = new PullRequestActivity();
			activity.setRequest(request);
			activity.setAction(PullRequestActivity.Action.INTEGRATE);
			activity.setDate(new Date());
			dao.persist(activity);
			
			request.setLastIntegrationPreview(null);
			request.setCloseStatus(CloseStatus.INTEGRATED);
			request.setLastEventDate(new Date());
			
			dao.persist(request);
			
			for (PullRequestListener listener: pullRequestListeners)
				listener.onIntegrated(request, null, null);
		} 
	}

	@Transactional
	@Override
	public void onSourceBranchUpdate(PullRequest request, boolean notify) {
		if (request.getLatestUpdate().getHeadCommitHash().equals(request.getSource().getHead()))
			return;
		
		PullRequestUpdate update = new PullRequestUpdate();
		update.setRequest(request);
		update.setDate(new Date());
		update.setHeadCommitHash(request.getSource().getHead());
		
		request.addUpdate(update);
		pullRequestUpdateManager.save(update, notify);
		closeIfMerged(request);

		if (request.isOpen()) {
			final Long requestId = request.getId();
			dao.afterCommit(new Runnable() {

				@Override
				public void run() {
					unitOfWork.asyncCall(new Runnable() {

						@Override
						public void run() {
							check(dao.load(PullRequest.class, requestId));
						}
						
					});
				}
				
			});
		}
	}

	/**
	 * This method might take some time and is not key to pull request logic (even if we 
	 * did not call it, we can always call it later), so normally should be called in an 
	 * executor
	 */
	@Transactional
	@Override
	public void check(PullRequest request) {
		Date now = new Date();
		if (request.isOpen()) {
			closeIfMerged(request);
			if (request.isOpen()) {
				if (request.getStatus() == PENDING_UPDATE) {
					for (PullRequestListener listener: pullRequestListeners)
						listener.pendingUpdate(request);
				} else if (request.getStatus() == PENDING_INTEGRATE) {
					for (PullRequestListener listener: pullRequestListeners)
						listener.pendingIntegration(request);
					
					IntegrationPreview integrationPreview = request.getIntegrationPreview();
					if (integrationPreview != null 
							&& integrationPreview.getIntegrated() != null 
							&& request.getAssignee() == null) {
						integrate(request, "Integrated automatically by system");
					}
				} else if (request.getStatus() == PENDING_APPROVAL) {
					for (PullRequestListener listener: pullRequestListeners)
						listener.pendingApproval(request);
					
					for (ReviewInvitation invitation: request.getReviewInvitations()) { 
						if (!invitation.getDate().before(now))
							reviewInvitationManager.save(invitation);
					}
				}
			}
		}
	}

	@Override
	public boolean canIntegrate(PullRequest request) {
		if (request.getStatus() != PENDING_INTEGRATE) {
			return false;
		} else {
			IntegrationPreview integrationPreview = request.getIntegrationPreview();
			return integrationPreview != null && integrationPreview.getIntegrated() != null;
		}
	}

	private int getIntegrationPreviewWorkers() {
		Integer workers = configManager.getQosSetting().getIntegrationPreviewWorkers();
		if (workers == null)
			workers = Runtime.getRuntime().availableProcessors();
		return workers;
	}

	@Override
	public void onSave(Config config) {
		if (config.getKey() == Config.Key.QOS && integrationPreviewExecutor != null) {
			int integrationPreviewWorkers = getIntegrationPreviewWorkers();
			integrationPreviewExecutor.setCorePoolSize(integrationPreviewWorkers);
			integrationPreviewExecutor.setMaximumPoolSize(integrationPreviewWorkers);
		}
	}

	@Override
	public IntegrationPreview previewIntegration(PullRequest request) {
		IntegrationPreview preview = request.getLastIntegrationPreview();
		if (request.isOpen() && (preview == null || preview.isObsolete(request))) {
			IntegrationPreviewTask task = new IntegrationPreviewTask(request.getId());
			integrationPreviewExecutor.remove(task);
			integrationPreviewExecutor.execute(task);
			return null;
		} else {
			return preview;
		}
	}
	
	private class IntegrationPreviewTask implements Runnable {

		private final Long requestId;
		
		public IntegrationPreviewTask(Long requestId) {
			this.requestId = requestId;
		}
		
		@Override
		public void run() {
			unitOfWork.begin();
			try {
				if (!integrationPreviewCalculatingRequestIds.contains(requestId)) {
					integrationPreviewCalculatingRequestIds.add(requestId);
					try {
						PullRequest request = dao.load(PullRequest.class, requestId);
						IntegrationPreview preview = request.getLastIntegrationPreview();
						if (request.isOpen() && (preview == null || preview.isObsolete(request))) {
							String requestHead = request.getLatestUpdate().getHeadCommitHash();
							String targetHead = request.getTarget().getHead();
							Git git = request.getTargetRepo().git();
							preview = new IntegrationPreview(targetHead, 
									request.getLatestUpdate().getHeadCommitHash(), request.getIntegrationStrategy(), null);
							request.setLastIntegrationPreview(preview);
							String integrateRef = request.getIntegrateRef();
							if (preview.getIntegrationStrategy() == MERGE_IF_NECESSARY && git.isAncestor(targetHead, requestHead)
									|| preview.getIntegrationStrategy() == MERGE_WITH_SQUASH && git.isAncestor(targetHead, requestHead)
											&& git.log(targetHead, requestHead, null, 0, 0).size() == 1) {
								preview.setIntegrated(requestHead);
								git.updateRef(integrateRef, requestHead, null, null);
							} else {
								File tempDir = FileUtils.createTempDir();
								try {
									Git tempGit = new Git(tempDir);
									tempGit.clone(git.repoDir().getAbsolutePath(), false, true, true, 
											request.getTargetBranch());
									
									String integrated;

									if (preview.getIntegrationStrategy() == REBASE_TARGET_ONTO_SOURCE) {
										tempGit.updateRef("HEAD", requestHead, null, null);
										tempGit.reset(null, null);
										List<String> cherries = tempGit.listCherries("HEAD", targetHead);
										integrated = tempGit.cherryPick(cherries.toArray(new String[cherries.size()]));
									} else {
										tempGit.updateRef("HEAD", targetHead, null, null);
										tempGit.reset(null, null);
										if (preview.getIntegrationStrategy() == REBASE_SOURCE_ONTO_TARGET) {
											List<String> cherries = tempGit.listCherries("HEAD", requestHead);
											integrated = tempGit.cherryPick(cherries.toArray(new String[cherries.size()]));
										} else if (preview.getIntegrationStrategy() == MERGE_WITH_SQUASH) {
											String commitMessage = request.getTitle() + "\n\n";
											if (request.getDescription() != null)
												commitMessage += request.getDescription() + "\n\n";
											commitMessage += "(squashed commit of pull request #" + request.getId() + ")\n";
											integrated = tempGit.squash(requestHead, null, null, commitMessage);
										} else {
											FastForwardMode fastForwardMode;
											if (preview.getIntegrationStrategy() == MERGE_ALWAYS)
												fastForwardMode = FastForwardMode.NO_FF;
											else 
												fastForwardMode = FastForwardMode.FF;
											String commitMessage = "Merge pull request #" + request.getId() 
													+ "\n\n" + request.getTitle() + "\n";
											integrated = tempGit.merge(requestHead, fastForwardMode, null, null, commitMessage);
										}
									}
									 
									if (integrated != null) {
										preview.setIntegrated(integrated);
										git.fetch(tempGit, "+HEAD:" + integrateRef);									
									} else {
										git.deleteRef(integrateRef, null, null);
									}
								} finally {
									FileUtils.deleteDir(tempDir);
								}
							}
							dao.persist(request);

							if (request.getStatus() == PENDING_INTEGRATE 
									&& preview.getIntegrated() != null
									&& request.getAssignee() == null) {
								integrate(request, "Integrated automatically by system");
							}
							
							for (PullRequestListener listener: pullRequestListeners)
								listener.onIntegrationPreviewCalculated(request);
						}
					} finally {
						integrationPreviewCalculatingRequestIds.remove(requestId);
					}
				}
			} catch (Exception e) {
				logger.error("Error calculating integration preview of pull request #" + requestId, e);
			} finally {
				unitOfWork.end();
			}
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof IntegrationPreviewTask))
				return false;
			if (this == other)
				return true;
			IntegrationPreviewTask otherRunnable = (IntegrationPreviewTask) other;
			return new EqualsBuilder().append(requestId, otherRunnable.requestId).isEquals();
		}

		@Override
		public int hashCode() {
			return new HashCodeBuilder(17, 37).append(requestId).toHashCode();
		}
		
	}

	@Override
	public Date getLastVisitDate(PullRequest request) {
		User user = userManager.getCurrent();
		if (user != null) {
			PullRequestVisit visit = request.getVisit(user);
			if (visit != null)
				return visit.getDate();
			else 
				return null;
		} else {
			return null;
		}
	}

	@Override
	public void systemStarting() {
		int previewWorkers = getIntegrationPreviewWorkers();
		integrationPreviewExecutor = new ThreadPoolExecutor(previewWorkers, previewWorkers, 
				0L, TimeUnit.MILLISECONDS, integrationPreviewQueue);
	}

	@Override
	public void systemStarted() {
	}

	@Override
	public void systemStopping() {
	}

	@Override
	public void systemStopped() {
		if (integrationPreviewExecutor != null)
			integrationPreviewExecutor.shutdown();
	}

	@Transactional
	@Override
	public void beforeDelete(Repository repository) {
    	for (PullRequest request: repository.getOutgoingRequests()) {
    		if (!request.getTargetRepo().equals(repository) && request.isOpen())
        		discard(request, "Source repository is deleted.");
    	}
    	
    	Query query = dao.getSession().createQuery("update PullRequest set sourceRepo=null where "
    			+ "sourceRepo = :repo and targetRepo != :repo");
    	query.setParameter("repo", repository);
    	query.executeUpdate();
	}

	@Override
	public void afterDelete(Repository repository) {
	}
	
	@Transactional
	@Override
	public void onRefUpdate(Repository repository, String refName, @Nullable String newCommitHash) {
		final String branch = GitUtils.ref2branch(refName);
		if (branch != null) {
			RepoAndBranch repoAndBranch = new RepoAndBranch(repository, branch);
			if (newCommitHash != null) {
				/**
				 * Source branch update is key to the logic as it has to create 
				 * pull request update, so we should not postpone it to be executed
				 * in a executor service like target branch update below
				 */
				Criterion criterion = Restrictions.and(ofOpen(), ofSource(repoAndBranch));
				for (PullRequest request: dao.query(EntityCriteria.of(PullRequest.class).add(criterion)))
					onSourceBranchUpdate(request, true);
				
				final Long repoId = repository.getId();
				dao.afterCommit(new Runnable() {

					@Override
					public void run() {
						unitOfWork.asyncCall(new Runnable() {

							@Override
							public void run() {
								RepoAndBranch repoAndBranch = new RepoAndBranch(repoId, branch);								
								Criterion criterion = Restrictions.and(ofOpen(), ofTarget(repoAndBranch));
								for (PullRequest request: dao.query(EntityCriteria.of(PullRequest.class).add(criterion))) 
									onTargetBranchUpdate(request);
							}
							
						});
					}
					
				});
			} else {
				Criterion criterion = Restrictions.and(
						ofOpen(), 
						Restrictions.or(ofSource(repoAndBranch), ofTarget(repoAndBranch)));
				for (PullRequest request: dao.query(EntityCriteria.of(PullRequest.class).add(criterion))) {
					if (request.getTargetRepo().equals(repository) && request.getTargetBranch().equals(branch)) 
						discard(request, "Target branch is deleted.");
					else
						discard(request, "Source branch is deleted.");
				}
			}
		}
	}

	@Sessional
	@Override
	public PullRequest findOpen(RepoAndBranch target, RepoAndBranch source) {
		return dao.find(EntityCriteria.of(PullRequest.class)
				.add(ofTarget(target)).add(ofSource(source)).add(ofOpen()));
	}

	@Sessional
	@Override
	public Collection<PullRequest> queryOpenTo(RepoAndBranch target, @Nullable Repository sourceRepo) {
		EntityCriteria<PullRequest> criteria = EntityCriteria.of(PullRequest.class);
		criteria.add(ofTarget(target));

		if (sourceRepo != null)
			criteria.add(Restrictions.eq("sourceRepo", sourceRepo));
		criteria.add(ofOpen());
		return dao.query(criteria);
	}

	@Sessional
	@Override
	public Collection<PullRequest> queryOpenFrom(RepoAndBranch source, @Nullable Repository targetRepo) {
		EntityCriteria<PullRequest> criteria = EntityCriteria.of(PullRequest.class);
		criteria.add(ofSource(source));
		
		if (targetRepo != null)
			criteria.add(Restrictions.eq("targetRepo", targetRepo));
		criteria.add(ofOpen());
		return dao.query(criteria);
	}

	@Sessional
	@Override
	public Collection<PullRequest> queryOpen(RepoAndBranch sourceOrTarget) {
		EntityCriteria<PullRequest> criteria = EntityCriteria.of(PullRequest.class);
		criteria.add(ofOpen());
		criteria.add(Restrictions.or(ofSource(sourceOrTarget), ofTarget(sourceOrTarget)));
		return dao.query(criteria);
	}

}

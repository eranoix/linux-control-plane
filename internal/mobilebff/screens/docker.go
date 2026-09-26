package screens

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"github.com/danielgtaylor/huma/v2/adapters/humago"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/image"
	"github.com/docker/docker/api/types/network"
	"github.com/docker/docker/api/types/volume"

	"server-control-panel/internal/auth"
	"server-control-panel/internal/config"
	docksvc "server-control-panel/internal/docker"
	"server-control-panel/internal/httpx"
	"server-control-panel/internal/mobilebff"
	"server-control-panel/internal/mobilebff/sdui"
)

// Screen ids — also the golden fixture filename stems (docker.containers.*,
// etc, see contracts/sdui/fixtures/screens/).
const (
	dockerContainersScreenID = "docker.containers"
	dockerImagesScreenID     = "docker.images"
	dockerVolumesScreenID    = "docker.volumes"
	dockerNetworksScreenID   = "docker.networks"
	dockerComposeScreenID    = "docker.compose"
	dockerPruneScreenID      = "docker.prune"
)

// Rows endpoints — one per table screen. Prune has no table, so no rows
// endpoint. Absolute paths (carry mobilebff.Prefix), same convention as
// schedulerJobsRowsEndpoint.
const (
	dockerContainersRowsEndpoint = mobilebff.Prefix + "/docker/containers"
	dockerImagesRowsEndpoint     = mobilebff.Prefix + "/docker/images"
	dockerVolumesRowsEndpoint    = mobilebff.Prefix + "/docker/volumes"
	dockerNetworksRowsEndpoint   = mobilebff.Prefix + "/docker/networks"
	dockerComposeRowsEndpoint    = mobilebff.Prefix + "/docker/compose"
)

// dockerTimestampFormat mirrors schedulerTimestampFormat — every timestamp
// in these six screens is rendered server-side, never a raw epoch.
const dockerTimestampFormat = "2006-01-02 15:04 UTC"

// RegisterDocker wires the six Docker screens, their actions and their rows
// endpoints. Called explicitly by internal/api/api.go, mirroring Register
// (scheduler.go) — a second top-level Register with a different parameter
// type is not legal Go, so this batch gets its own name; every later
// section in this phase follows the same RegisterX convention.
func RegisterDocker(deps DockerDeps) {
	sdui.Register(dockerContainersScreenID, func(_ context.Context, v sdui.Viewer) (*sdui.Envelope, error) {
		return buildDockerContainersScreen(v), nil
	})
	sdui.Register(dockerImagesScreenID, func(_ context.Context, v sdui.Viewer) (*sdui.Envelope, error) {
		return buildDockerImagesScreen(v), nil
	})
	sdui.Register(dockerVolumesScreenID, func(_ context.Context, _ sdui.Viewer) (*sdui.Envelope, error) {
		return buildDockerVolumesScreen(), nil
	})
	sdui.Register(dockerNetworksScreenID, func(_ context.Context, _ sdui.Viewer) (*sdui.Envelope, error) {
		return buildDockerNetworksScreen(), nil
	})
	sdui.Register(dockerComposeScreenID, func(_ context.Context, v sdui.Viewer) (*sdui.Envelope, error) {
		return buildDockerComposeScreen(v), nil
	})
	sdui.Register(dockerPruneScreenID, func(_ context.Context, v sdui.Viewer) (*sdui.Envelope, error) {
		return buildDockerPruneScreenForViewer(v)
	})

	// Catalog entries (the app's section picker). The labels qualify the
	// generic noun — "Imagens", "Volumes" and "Redes" on their own do not say
	// Docker, and the label has to make sense read outside the group (in a
	// search, in a recent item). Only docker.prune is somenteAdmin: it is the
	// only one of the six whose builder (buildDockerPruneScreenForViewer)
	// refuses a non-admin with ErrScreenNotFound; the other five assemble for
	// any viewer and merely omit destructive actions from inside the Envelope.
	sdui.RegisterCatalog(dockerContainersScreenID, sdui.GroupDocker, "Containers", sempreVisivel)
	sdui.RegisterCatalog(dockerImagesScreenID, sdui.GroupDocker, "Docker images", sempreVisivel)
	sdui.RegisterCatalog(dockerVolumesScreenID, sdui.GroupDocker, "Docker volumes", sempreVisivel)
	sdui.RegisterCatalog(dockerNetworksScreenID, sdui.GroupDocker, "Docker networks", sempreVisivel)
	sdui.RegisterCatalog(dockerComposeScreenID, sdui.GroupDocker, "Compose", sempreVisivel)
	sdui.RegisterCatalog(dockerPruneScreenID, sdui.GroupDocker, "Limpeza do Docker", somenteAdmin)

	registerDockerActions(deps)

	mobilebff.Register("docker.containers.rows", func(api huma.API, mbDeps mobilebff.Deps) {
		registerDockerContainersRows(api, deps, mbDeps)
	})
	mobilebff.Register("docker.images.rows", func(api huma.API, mbDeps mobilebff.Deps) {
		registerDockerImagesRows(api, deps, mbDeps)
	})
	mobilebff.Register("docker.volumes.rows", func(api huma.API, mbDeps mobilebff.Deps) {
		registerDockerVolumesRows(api, deps, mbDeps)
	})
	mobilebff.Register("docker.networks.rows", func(api huma.API, mbDeps mobilebff.Deps) {
		registerDockerNetworksRows(api, deps, mbDeps)
	})
	mobilebff.Register("docker.compose.rows", func(api huma.API, mbDeps mobilebff.Deps) {
		registerDockerComposeRows(api, deps, mbDeps)
	})

	// Forbidden-for-non-admin ledger: containers/images/compose each hide
	// their one destructive row action id from non-admin. Rows themselves
	// are NOT filtered — internal/docker (and the web panel's own
	// handleContainers/handleImages/handleCompose) apply no per-viewer
	// scoping beyond admin/non-admin today, so the non-admin envelope keeps
	// every row and omits only the destructive action (see PLAN.md Risks:
	// "the non-admin screens simply omit every destructive action and keep
	// all rows"). Volumes and networks have no destructive action for any
	// viewer, so they are registered in screensWithNoRoleDifference instead
	// (golden_test.go), not here.
	sdui.RegisterForbiddenForNonAdmin(dockerContainersScreenID, func() []string {
		return []string{dockerActionContainerRemove}
	})
	sdui.RegisterForbiddenForNonAdmin(dockerImagesScreenID, func() []string {
		return []string{dockerActionImageRemove}
	})
	sdui.RegisterForbiddenForNonAdmin(dockerComposeScreenID, func() []string {
		return []string{dockerActionComposeDown}
	})
	sdui.RegisterForbiddenForNonAdmin(dockerPruneScreenID, func() []string {
		// The whole screen is admin-only (ErrScreenNotFound above), so
		// there is no non-admin envelope to omit anything FROM — the
		// forbidden set only needs to cover the case where the harness
		// still probes prune.run's action id directly (see
		// TestGoldenScreens_NonAdminNeverContainsForbiddenStrings and
		// Task 2 Test 3: non-admin invoking it gets ErrActionNotFound).
		return []string{dockerActionPruneRun}
	})
}

// buildDockerContainersScreen builds the containers-table screen. Row
// actions (start/stop/restart) are visible to every viewer — the web panel
// applies no admin gate to container lifecycle control today (verified
// against handlers_docker.go); remove is destructive and admin-only, and is
// dropped from the table for non-admins via DropRowActions, the only
// sanctioned removal mechanism (filter.go).
func buildDockerContainersScreen(v sdui.Viewer) *sdui.Envelope {
	table := sdui.TableComponent{
		ComponentBase: sdui.ComponentBase{Type: sdui.ComponentTypeTable, ID: "containers-table"},
		Columns: []sdui.TableColumn{
			{Key: "name", Label: "Nome", Kind: "text"},
			{Key: "image", Label: "Imagem", Kind: "text"},
			{Key: "status", Label: "Status", Kind: "badge", BadgeMap: map[string]string{
				"running": "success", "restarting": "warning", "paused": "neutral",
				"exited": "neutral", "dead": "danger", "created": "neutral",
			}},
			{Key: "created", Label: "Criado em", Kind: "text"},
		},
		RowsSource: sdui.DataSource{Endpoint: dockerContainersRowsEndpoint},
		RowActions: []sdui.ActionRef{
			{ActionID: dockerActionContainerStart, Label: "Iniciar", Style: "secondary"},
			{ActionID: dockerActionContainerStop, Label: "Parar", Style: "secondary"},
			{ActionID: dockerActionContainerRestart, Label: "Reiniciar", Style: "secondary"},
			{ActionID: dockerActionContainerRemove, Label: "Remover", Style: "destructive"},
		},
		EmptyState: &sdui.EmptyState{Text: "Containers são as unidades que o Docker roda neste servidor; a lista traz os de pé e também os parados. Vazio é incomum aqui, porque o próprio painel roda em container — costuma significar que a stack foi derrubada. Em Docker › Compose você sobe uma stack inteira de volta."},
	}
	if !v.IsAdmin() {
		sdui.DropRowActions(&table, func(a sdui.ActionRef) bool { return a.ActionID != dockerActionContainerRemove })
	}

	confirm := sdui.ConfirmDestructiveComponent{
		ComponentBase: sdui.ComponentBase{Type: sdui.ComponentTypeConfirmDestructive, ID: "container-remove-confirm"},
		ActionID:      dockerActionContainerRemove,
		Message:       "Este container será removido. Se ele estiver em execução, a remoção falha a menos que 'force' seja usado.",
	}

	screen := sdui.Screen{ID: dockerContainersScreenID, Title: "Containers", Components: []sdui.Component{table}}
	if v.IsAdmin() {
		screen.Components = append(screen.Components, confirm)
	}
	return &sdui.Envelope{Screen: screen}
}

// buildDockerImagesScreen builds the images-table screen. remove is
// destructive and admin-only.
func buildDockerImagesScreen(v sdui.Viewer) *sdui.Envelope {
	table := sdui.TableComponent{
		ComponentBase: sdui.ComponentBase{Type: sdui.ComponentTypeTable, ID: "images-table"},
		Columns: []sdui.TableColumn{
			{Key: "repo_tag", Label: "Repositório:Tag", Kind: "text"},
			{Key: "size", Label: "Tamanho", Kind: "text"},
			{Key: "created", Label: "Criada em", Kind: "text"},
		},
		RowsSource: sdui.DataSource{Endpoint: dockerImagesRowsEndpoint},
		RowActions: []sdui.ActionRef{
			{ActionID: dockerActionImageRemove, Label: "Remover", Style: "destructive"},
		},
		EmptyState: &sdui.EmptyState{Text: "Imagens são o molde de onde cada container nasce; aqui está o que já foi baixado ou construído neste servidor. Sem nenhuma imagem, nenhum container sobe — o normal é ter pelo menos as da stack em execução. Elas voltam sozinhas no próximo pull ou build de uma stack Compose."},
	}
	if !v.IsAdmin() {
		sdui.DropRowActions(&table, func(a sdui.ActionRef) bool { return a.ActionID != dockerActionImageRemove })
	}

	confirm := sdui.ConfirmDestructiveComponent{
		ComponentBase: sdui.ComponentBase{Type: sdui.ComponentTypeConfirmDestructive, ID: "image-remove-confirm"},
		ActionID:      dockerActionImageRemove,
		Message:       "Esta imagem será removida. Se estiver em uso por um container, a remoção falha a menos que 'force' seja usado.",
	}

	screen := sdui.Screen{ID: dockerImagesScreenID, Title: "Imagens", Components: []sdui.Component{table}}
	if v.IsAdmin() {
		screen.Components = append(screen.Components, confirm)
	}
	return &sdui.Envelope{Screen: screen}
}

// buildDockerVolumesScreen builds the volumes-table screen: list-only for
// EVERY viewer, admin included — internal/docker exposes no single-item
// volume delete anywhere (only the bulk VolumesPrune reachable from
// docker.prune). No row actions, no confirm_destructive component; identical
// for admin and non-admin (screensWithNoRoleDifference in golden_test.go).
func buildDockerVolumesScreen() *sdui.Envelope {
	table := sdui.TableComponent{
		ComponentBase: sdui.ComponentBase{Type: sdui.ComponentTypeTable, ID: "volumes-table"},
		Columns: []sdui.TableColumn{
			{Key: "name", Label: "Nome", Kind: "text"},
			{Key: "driver", Label: "Driver", Kind: "text"},
			{Key: "size", Label: "Tamanho", Kind: "text"},
		},
		RowsSource: sdui.DataSource{Endpoint: dockerVolumesRowsEndpoint},
		EmptyState: &sdui.EmptyState{Text: "Volumes são o pedaço de disco que sobrevive ao container: banco, uploads, cache. Vazio é normal quando a stack usa só bind mounts de pastas do host. Não há como criar um volume por aqui — ele nasce junto com o container que o declara."},
	}
	screen := sdui.Screen{ID: dockerVolumesScreenID, Title: "Volumes", Components: []sdui.Component{table}}
	return &sdui.Envelope{Screen: screen}
}

// buildDockerNetworksScreen builds the networks-table screen: list-only for
// every viewer, same reasoning as buildDockerVolumesScreen — internal/docker
// exposes no single-item network delete anywhere, and system networks
// (bridge/host/none) must never gain a remove action regardless of RBAC.
func buildDockerNetworksScreen() *sdui.Envelope {
	table := sdui.TableComponent{
		ComponentBase: sdui.ComponentBase{Type: sdui.ComponentTypeTable, ID: "networks-table"},
		Columns: []sdui.TableColumn{
			{Key: "name", Label: "Nome", Kind: "text"},
			{Key: "driver", Label: "Driver", Kind: "text"},
			{Key: "scope", Label: "Escopo", Kind: "text"},
		},
		RowsSource: sdui.DataSource{Endpoint: dockerNetworksRowsEndpoint},
		EmptyState: &sdui.EmptyState{Text: "Redes Docker decidem quais containers enxergam quais. Esta lista não deveria ficar vazia nunca: o Docker mantém bridge, host e none mesmo num servidor sem nada rodando. Vazia significa resposta incompleta do Docker — vale conferir o docker.service em Serviços (systemd)."},
	}
	screen := sdui.Screen{ID: dockerNetworksScreenID, Title: "Redes", Components: []sdui.Component{table}}
	return &sdui.Envelope{Screen: screen}
}

// buildDockerComposeScreen builds the compose-table screen. up is visible to
// every viewer (same no-admin-gate-on-lifecycle reasoning as containers);
// down is destructive and admin-only.
func buildDockerComposeScreen(v sdui.Viewer) *sdui.Envelope {
	table := sdui.TableComponent{
		ComponentBase: sdui.ComponentBase{Type: sdui.ComponentTypeTable, ID: "compose-table"},
		Columns: []sdui.TableColumn{
			{Key: "stack", Label: "Stack", Kind: "text"},
			{Key: "status", Label: "Status", Kind: "badge", BadgeMap: map[string]string{
				"running": "success", "partial": "warning", "stopped": "neutral", "unknown": "neutral",
			}},
		},
		RowsSource: sdui.DataSource{Endpoint: dockerComposeRowsEndpoint},
		RowActions: []sdui.ActionRef{
			{ActionID: dockerActionComposeUp, Label: "Subir", Style: "secondary"},
			{ActionID: dockerActionComposeDown, Label: "Derrubar", Style: "destructive"},
		},
		EmptyState: &sdui.EmptyState{Text: "Cada linha é um projeto Compose, agrupando os containers que subiram juntos. A lista é descoberta pelos rótulos dos containers, não por arquivos no disco: um docker-compose.yml que nunca subiu não aparece aqui. Suba a stack pelo host e ela entra na lista, com as ações que a sua permissão alcança na própria linha."},
	}
	if !v.IsAdmin() {
		sdui.DropRowActions(&table, func(a sdui.ActionRef) bool { return a.ActionID != dockerActionComposeDown })
	}

	confirm := sdui.ConfirmDestructiveComponent{
		ComponentBase: sdui.ComponentBase{Type: sdui.ComponentTypeConfirmDestructive, ID: "compose-down-confirm"},
		ActionID:      dockerActionComposeDown,
		Message:       "Este stack será derrubado (docker compose down). Os containers dos serviços dele param e são removidos.",
	}

	screen := sdui.Screen{ID: dockerComposeScreenID, Title: "Compose", Components: []sdui.Component{table}}
	if v.IsAdmin() {
		screen.Components = append(screen.Components, confirm)
	}
	return &sdui.Envelope{Screen: screen}
}

// buildDockerPruneScreen builds the prune-form screen — form only, no
// table, per PLAN.md's shape ("prune is form + confirm_destructive only").
// Only reachable by an admin viewer (RegisterDocker gates Build itself).
// RequireTypedConfirmation is set (unlike scheduler.job.delete's empty
// value) because a system prune is a genuinely irreversible, root-adjacent,
// potentially large-blast-radius operation — the opposite case from a
// recreatable scheduler job, where scheduler.go deliberately left this
// empty to avoid training the user to type without reading.
// buildDockerPruneScreenForViewer applies the admin-only gate (a non-admin's
// Build call returns ErrScreenNotFound, the same 404-never-403 posture every
// other admin-only surface in this package uses — never an emptied envelope
// that still confirms the screen exists) and delegates to
// buildDockerPruneScreen. Split out, like buildSchedulerJobsScreen, so tests
// can exercise the gate directly without going through the global registry.
func buildDockerPruneScreenForViewer(v sdui.Viewer) (*sdui.Envelope, error) {
	if !v.IsAdmin() {
		return nil, sdui.ErrScreenNotFound
	}
	return buildDockerPruneScreen(), nil
}

func buildDockerPruneScreen() *sdui.Envelope {
	form := sdui.FormComponent{
		ComponentBase: sdui.ComponentBase{Type: sdui.ComponentTypeForm, ID: "prune-form"},
		Fields: []sdui.FormField{
			{Key: "containers", Label: "Containers parados", Kind: "bool"},
			{Key: "images", Label: "Imagens não usadas", Kind: "bool"},
			{Key: "volumes", Label: "Volumes não usados", Kind: "bool"},
			{Key: "build_cache", Label: "Cache de build", Kind: "bool"},
		},
		SubmitAction: sdui.ActionRef{ActionID: dockerActionPruneRun, Label: "Limpar", Style: "destructive"},
	}

	confirm := sdui.ConfirmDestructiveComponent{
		ComponentBase:            sdui.ComponentBase{Type: sdui.ComponentTypeConfirmDestructive, ID: "prune-confirm"},
		ActionID:                 dockerActionPruneRun,
		Message:                  "Isto remove permanentemente os dados Docker não usados nas categorias marcadas. Não pode ser desfeito.",
		RequireTypedConfirmation: "PRUNE",
	}

	screen := sdui.Screen{
		ID:         dockerPruneScreenID,
		Title:      "Limpeza do Docker",
		Components: []sdui.Component{form, confirm},
	}
	return &sdui.Envelope{Screen: screen}
}

// --- Rows endpoints -------------------------------------------------------

func registerDockerContainersRows(api huma.API, deps DockerDeps, mbDeps mobilebff.Deps) {
	registerDockerRows(api, "getDockerContainerRows", "/docker/containers", "Linhas de docker.containers", mbDeps.Cfg,
		func(ctx context.Context, _ sdui.Viewer) ([]map[string]any, error) {
			list, err := deps.ListContainers(ctx)
			if err != nil {
				return nil, err
			}
			rows := make([]map[string]any, 0, len(list))
			for _, c := range list {
				rows = append(rows, dockerContainerRow(c))
			}
			return rows, nil
		})
}

func registerDockerImagesRows(api huma.API, deps DockerDeps, mbDeps mobilebff.Deps) {
	registerDockerRows(api, "getDockerImageRows", "/docker/images", "Linhas de docker.images", mbDeps.Cfg,
		func(ctx context.Context, _ sdui.Viewer) ([]map[string]any, error) {
			list, err := deps.ListImages(ctx)
			if err != nil {
				return nil, err
			}
			rows := make([]map[string]any, 0, len(list))
			for _, im := range list {
				rows = append(rows, dockerImageRow(im))
			}
			return rows, nil
		})
}

func registerDockerVolumesRows(api huma.API, deps DockerDeps, mbDeps mobilebff.Deps) {
	registerDockerRows(api, "getDockerVolumeRows", "/docker/volumes", "Linhas de docker.volumes", mbDeps.Cfg,
		func(ctx context.Context, _ sdui.Viewer) ([]map[string]any, error) {
			list, err := deps.ListVolumes(ctx)
			if err != nil {
				return nil, err
			}
			rows := make([]map[string]any, 0, len(list.Volumes))
			for _, vol := range list.Volumes {
				if vol == nil {
					continue
				}
				rows = append(rows, dockerVolumeRow(*vol))
			}
			return rows, nil
		})
}

func registerDockerNetworksRows(api huma.API, deps DockerDeps, mbDeps mobilebff.Deps) {
	registerDockerRows(api, "getDockerNetworkRows", "/docker/networks", "Linhas de docker.networks", mbDeps.Cfg,
		func(ctx context.Context, _ sdui.Viewer) ([]map[string]any, error) {
			list, err := deps.ListNetworks(ctx)
			if err != nil {
				return nil, err
			}
			rows := make([]map[string]any, 0, len(list))
			for _, n := range list {
				rows = append(rows, dockerNetworkRow(n))
			}
			return rows, nil
		})
}

func registerDockerComposeRows(api huma.API, deps DockerDeps, mbDeps mobilebff.Deps) {
	registerDockerRows(api, "getDockerComposeRows", "/docker/compose", "Linhas de docker.compose", mbDeps.Cfg,
		func(ctx context.Context, _ sdui.Viewer) ([]map[string]any, error) {
			list, err := deps.ListComposeStacks(ctx)
			if err != nil {
				return nil, err
			}
			rows := make([]map[string]any, 0, len(list))
			for _, p := range list {
				rows = append(rows, dockerComposeRow(p))
			}
			return rows, nil
		})
}

// registerDockerRows is the shared plumbing for all five Docker rows
// endpoints: authenticate, resolve Viewer, call fetch, wrap as {"rows":[...]}
// — the same wire shape scheduler.jobs' rows endpoint established
// (registerSchedulerRows), reused here instead of re-deriving it five times.
func registerDockerRows(api huma.API, opID, path, summary string, cfg *config.Config, fetch func(context.Context, sdui.Viewer) ([]map[string]any, error)) {
	huma.Register(api, huma.Operation{
		OperationID: opID,
		Method:      http.MethodGet,
		Path:        path,
		Summary:     summary,
		Tags:        []string{"mobile", "sdui", "docker"},
		Middlewares: huma.Middlewares{mobilebff.RequireAuth, serveDockerRows(cfg, fetch)},
	}, dockerRowsDocHandler)
}

type dockerRowsInput struct{}

type dockerRowsOutput struct {
	Body json.RawMessage
}

func dockerRowsDocHandler(_ context.Context, _ *dockerRowsInput) (*dockerRowsOutput, error) {
	return &dockerRowsOutput{Body: json.RawMessage(`{"rows":[]}`)}, nil
}

func serveDockerRows(cfg *config.Config, fetch func(context.Context, sdui.Viewer) ([]map[string]any, error)) func(huma.Context, func(huma.Context)) {
	return func(ctx huma.Context, next func(huma.Context)) {
		req, w := humago.Unwrap(ctx)

		username := auth.UserFrom(req)
		v := sdui.ViewerFrom(cfg, username)

		rows, err := fetch(req.Context(), v)
		if err != nil {
			log.Printf("mobilebff/screens: error fetching the docker rows: %v", err)
			httpx.WriteErr(w, http.StatusInternalServerError, "internal_error")
			return
		}
		if rows == nil {
			rows = []map[string]any{}
		}

		body, err := json.Marshal(map[string]any{"rows": rows})
		if err != nil {
			log.Printf("mobilebff/screens: error serializing the docker rows: %v", err)
			httpx.WriteErr(w, http.StatusInternalServerError, "internal_error")
			return
		}

		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(body)
	}
}

// --- Row shaping -----------------------------------------------------------

// dockerContainerRow shapes one types.Container into the row wire format.
// Wire shape: {"id","name","image","status","created"}.
func dockerContainerRow(c types.Container) map[string]any {
	name := c.ID
	if len(c.Names) > 0 {
		name = strings.TrimPrefix(c.Names[0], "/")
	}
	return map[string]any{
		"id":      c.ID,
		"name":    name,
		"image":   c.Image,
		"status":  c.State,
		"created": formatDockerTimestamp(c.Created),
	}
}

// dockerImageRow shapes one image.Summary into the row wire format.
// Wire shape: {"id","repo_tag","size","created"}.
func dockerImageRow(im image.Summary) map[string]any {
	repoTag := "(untagged)"
	if len(im.RepoTags) > 0 {
		repoTag = im.RepoTags[0]
	}
	return map[string]any{
		"id":       im.ID,
		"repo_tag": repoTag,
		"size":     formatDockerBytes(im.Size),
		"created":  formatDockerTimestamp(im.Created),
	}
}

// dockerVolumeRow shapes one volume.Volume into the row wire format. Size
// comes from UsageData, which is only populated by the disk-usage
// aggregation, not the plain list call docker.Client.Volumes makes — a
// volume whose usage was never computed shows an empty size, never a
// misleading 0 B or -1.
// Wire shape: {"id","name","driver","size"}.
func dockerVolumeRow(v volume.Volume) map[string]any {
	size := ""
	if v.UsageData != nil && v.UsageData.Size >= 0 {
		size = formatDockerBytes(v.UsageData.Size)
	}
	return map[string]any{
		"id":     v.Name,
		"name":   v.Name,
		"driver": v.Driver,
		"size":   size,
	}
}

// dockerNetworkRow shapes one network.Summary into the row wire format.
// Wire shape: {"id","name","driver","scope"}.
func dockerNetworkRow(n network.Summary) map[string]any {
	return map[string]any{
		"id":     n.ID,
		"name":   n.Name,
		"driver": n.Driver,
		"scope":  n.Scope,
	}
}

// dockerComposeRow shapes one docksvc.ComposeProject into the row wire
// format. Wire shape: {"id","stack","status"} — "id" and "stack" are both
// the project name (compose has no separate id), kept as two keys so the
// client's generic row-rendering code (which reads "id" for row identity on
// every table) stays uniform across all six Docker screens.
func dockerComposeRow(p docksvc.ComposeProject) map[string]any {
	return map[string]any{
		"id":     p.Name,
		"stack":  p.Name,
		"status": p.Status,
	}
}

// formatDockerTimestamp renders a Unix epoch as the server-formatted display
// string every timestamp in these six screens uses — mirrors
// formatSchedulerTimestamp's zero-is-empty rule.
func formatDockerTimestamp(epoch int64) string {
	if epoch == 0 {
		return ""
	}
	return time.Unix(epoch, 0).UTC().Format(dockerTimestampFormat)
}

// formatDockerBytes renders a byte count as a human-readable, display-ready
// string (never a raw number) — -1 (Docker's "not calculated" sentinel,
// used by both image.Summary.Size and volume.UsageData.Size) renders as
// empty, matching formatDockerTimestamp's "unknown/unset" convention.
func formatDockerBytes(n int64) string {
	if n < 0 {
		return ""
	}
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(n)/float64(div), "KMGTPE"[exp])
}

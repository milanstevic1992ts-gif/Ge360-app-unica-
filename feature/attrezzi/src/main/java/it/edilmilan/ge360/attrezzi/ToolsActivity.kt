package it.edilmilan.ge360.attrezzi

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import java.time.LocalDate
import java.util.Locale

class ToolsActivity : AppCompatActivity() {
    private lateinit var store: ToolsStore
    private lateinit var jobsites: JobsiteStore
    private lateinit var jobSpinner: Spinner
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var adapter: RowAdapter
    private var jobRows: List<JobsiteRecord> = emptyList()
    private var mode = MODE_PREP
    private var replaceOnImport = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ToolsStore(this)
        jobsites = JobsiteStore(this)
        jobRows = jobsites.list()
        setContentView(buildScreen())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            val keep = selectedJob()?.ge360Id
            jobRows = jobsites.list()
            installJobSpinner(keep)
            refresh()
        }
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(10)) }
        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Attrezzi & materiali",
            subtitle = "Preparazione cantiere e squadra",
            onBack = { finish() },
            trailingLabel = "Dati",
            onTrailingClick = { dataMenu() }
        ))

        jobSpinner = Spinner(this)
        installJobSpinner(null)
        root.addView(jobSpinner, lp52())

        status = TextView(this).apply { textSize = 13f; setPadding(dp(4), dp(5), dp(4), dp(7)) }
        root.addView(status)

        val modeBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(MODE_PREP to "Preparazione", MODE_CATALOG to "Catalogo", MODE_REQUESTS to "Richieste", MODE_TASKS to "Squadra").forEach { (id, label) ->
            modeBar.addView(MaterialButton(this).apply {
                text = label; textSize = 11f
                setOnClickListener { mode = id; refresh() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(6) })
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(modeBar) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        search = EditText(this).apply {
            hint = "Cerca attrezzo, materiale, richiesta, task…"; isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, lp52())

        adapter = RowAdapter()
        root.addView(ListView(this).apply {
            dividerHeight = 0; adapter = this@ToolsActivity.adapter
            setOnItemClickListener { _, _, p, _ -> openRow(this@ToolsActivity.adapter.rows[p]) }
            setOnItemLongClickListener { _, _, p, _ -> editRow(this@ToolsActivity.adapter.rows[p]); true }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(MaterialButton(this).apply {
            id = View.generateViewId(); text = "+ Aggiungi"
            setOnClickListener { addForMode() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        return root
    }

    private fun installJobSpinner(keepId: String?) {
        if (!::jobSpinner.isInitialized) return
        val labels = mutableListOf("Tutti i cantieri / nessuno").apply { addAll(jobRows.map { "${it.code} · ${it.displayTitle()}" }) }
        jobSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val index = jobRows.indexOfFirst { it.ge360Id == keepId }
        jobSpinner.setSelection(if (index >= 0) index + 1 else 0)
        jobSpinner.onItemSelectedListener = Selected { refresh() }
    }

    private fun selectedJob(): JobsiteRecord? = jobRows.getOrNull(jobSpinner.selectedItemPosition - 1)

    private fun refresh() {
        if (!::adapter.isInitialized) return
        val job = selectedJob()
        val q = if (::search.isInitialized) search.text.toString().trim().lowercase(Locale.ITALY) else ""
        val rows = when (mode) {
            MODE_CATALOG -> store.catalog(q).map { Row("catalog", it.id, it.name, "${kindLabel(it.kind)} · ${it.category} · ${it.unit}${if (it.note.isBlank()) "" else " · ${it.note}"}", it) }
            MODE_REQUESTS -> store.requests(job?.ge360Id.orEmpty()).filter { match(q, it.name, it.note, it.requestedBy, it.status) }.map { Row("request", it.id, "${if (it.urgent) "⚠ " else ""}${it.name}", "${it.quantity.clean()} ${it.unit} · ${it.status}${if (it.requestedBy.isBlank()) "" else " · ${it.requestedBy}"}${if (it.jobsiteCode.isBlank()) "" else " · ${it.jobsiteCode}"}", it) }
            MODE_TASKS -> store.tasks(job?.ge360Id.orEmpty()).filter { match(q, it.title, it.description, it.assignedTo, it.status, it.type) }.map { Row("task", it.id, "${priorityIcon(it.priority)}${it.title}", "${it.type} · ${it.status}${if (it.assignedTo.isBlank()) "" else " · ${it.assignedTo}"}${if (it.dueDate.isBlank()) "" else " · ${it.dueDate}"}", it) }
            else -> if (job == null) emptyList() else store.jobResources(job.ge360Id).filter { match(q, it.name, it.note, it.status) }.map { Row("resource", it.id, it.name, "${kindLabel(it.kind)} · ${it.quantity.clean()} ${it.unit} · ${it.status}${if (it.note.isBlank()) "" else " · ${it.note}"}", it) }
        }
        adapter.rows = rows; adapter.notifyDataSetChanged()
        status.text = when {
            mode == MODE_CATALOG -> "Catalogo GE360 · ${rows.size} voci visibili"
            job == null -> if (mode == MODE_PREP) "Scegli un cantiere per preparare attrezzi e materiali." else "Vista globale · ${rows.size} elementi"
            else -> {
                val s = store.summary(job.ge360Id)
                "${job.code} · 🔧 ${s.tools} · 📦 ${s.materials} · da preparare ${s.toBring} · mancanti ${s.missing} · richieste ${s.openRequests} · task ${s.openTasks}"
            }
        }
    }

    private fun addForMode() {
        when (mode) {
            MODE_CATALOG -> editCatalog(CatalogResource())
            MODE_REQUESTS -> editRequest(ResourceRequest())
            MODE_TASKS -> editTask(TeamTask(dueDate = LocalDate.now().toString()))
            else -> pickCatalogForJob()
        }
    }

    private fun openRow(row: Row) {
        when (row.type) {
            "catalog" -> addCatalogToJob(row.payload as CatalogResource)
            "resource" -> chooseResourceStatus(row.payload as JobResource)
            "request" -> chooseRequestStatus(row.payload as ResourceRequest)
            "task" -> chooseTaskStatus(row.payload as TeamTask)
        }
    }

    private fun editRow(row: Row) {
        when (row.type) {
            "catalog" -> editCatalog((row.payload as CatalogResource).copy())
            "resource" -> editJobResource((row.payload as JobResource).copy())
            "request" -> editRequest((row.payload as ResourceRequest).copy())
            "task" -> editTask((row.payload as TeamTask).copy())
        }
    }

    private fun pickCatalogForJob() {
        val job = selectedJob() ?: return toast("Scegli prima un cantiere")
        val rows = store.catalog()
        AlertDialog.Builder(this).setTitle("Aggiungi a ${job.code}")
            .setItems(rows.map { "${kindLabel(it.kind)} · ${it.name}" }.toTypedArray()) { _, p -> addCatalogToJob(rows[p]) }.show()
    }

    private fun addCatalogToJob(c: CatalogResource) {
        val job = selectedJob() ?: return toast("Scegli prima un cantiere")
        editJobResource(JobResource(jobsiteId = job.ge360Id, jobsiteCode = job.code, jobsiteTitle = job.displayTitle(), catalogId = c.id, catalogGe360Id = c.ge360Id, kind = c.kind, name = c.name, unit = c.unit, note = c.note))
    }

    private fun editJobResource(item: JobResource) {
        val job = selectedJob() ?: return toast("Scegli prima un cantiere")
        if (item.jobsiteId.isBlank()) { item.jobsiteId = job.ge360Id; item.jobsiteCode = job.code; item.jobsiteTitle = job.displayTitle() }
        val box = formBox()
        val name = field(item.name, "Nome"); val qty = numberField(item.quantity); val unit = spinner(ToolsStore.UNITS, item.unit); val statusSpin = spinner(ToolsStore.RESOURCE_STATUSES, item.status); val note = multiline(item.note, "Nota")
        addLabeled(box, "Nome", name); addLabeled(box, "Quantità", qty); addLabeled(box, "Unità", unit); addLabeled(box, "Stato", statusSpin); addLabeled(box, "Nota", note, 82)
        val d = AlertDialog.Builder(this).setTitle("Dotazione cantiere").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Salva", null).setNegativeButton("Annulla", null).create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (name.text.toString().isBlank()) { name.error = "Nome obbligatorio"; return@setOnClickListener }
            item.name = name.text.toString().trim(); item.quantity = parseNumber(qty.text.toString()).coerceAtLeast(0.01); item.unit = unit.selectedItem.toString(); item.status = statusSpin.selectedItem.toString(); item.note = note.text.toString().trim(); store.saveJobResource(item); d.dismiss(); refresh()
        } }; d.show()
    }

    private fun chooseResourceStatus(item: JobResource) = AlertDialog.Builder(this).setTitle(item.name).setItems(ToolsStore.RESOURCE_STATUSES.toTypedArray()) { _, p -> item.status = ToolsStore.RESOURCE_STATUSES[p]; store.saveJobResource(item); refresh() }.setNeutralButton("Modifica") { _, _ -> editJobResource(item.copy()) }.show()

    private fun editCatalog(item: CatalogResource) {
        val box = formBox(); val name = field(item.name, "Nome"); val kind = spinner(listOf("Attrezzo", "Materiale"), if (item.kind == ResourceKind.TOOL) "Attrezzo" else "Materiale"); val category = field(item.category, "Categoria"); val unit = spinner(ToolsStore.UNITS, item.unit); val note = multiline(item.note, "Nota")
        addLabeled(box,"Nome",name); addLabeled(box,"Tipo",kind); addLabeled(box,"Categoria",category); addLabeled(box,"Unità",unit); addLabeled(box,"Nota",note,82)
        val d=AlertDialog.Builder(this).setTitle(if(item.id>0)"Modifica catalogo" else "Nuova voce catalogo").setView(ScrollView(this).apply{addView(box)}).setPositiveButton("Salva",null).setNegativeButton("Annulla",null).create()
        d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{if(name.text.toString().isBlank()){name.error="Nome obbligatorio";return@setOnClickListener};item.name=name.text.toString().trim();item.kind=if(kind.selectedItemPosition==0)ResourceKind.TOOL else ResourceKind.MATERIAL;item.category=category.text.toString().trim().ifBlank{"Altro"};item.unit=unit.selectedItem.toString();item.note=note.text.toString().trim();store.saveCatalog(item);d.dismiss();refresh()}};d.show()
    }

    private fun editRequest(item: ResourceRequest) {
        val job=selectedJob(); if(item.jobsiteId.isBlank()){if(job==null)return toast("Scegli prima un cantiere");item.jobsiteId=job.ge360Id;item.jobsiteCode=job.code;item.jobsiteTitle=job.displayTitle()}
        val box=formBox(); val name=field(item.name,"Materiale / attrezzo"); val kind=spinner(listOf("Materiale","Attrezzo"),if(item.kind==ResourceKind.MATERIAL)"Materiale" else "Attrezzo"); val qty=numberField(item.quantity); val unit=spinner(ToolsStore.UNITS,item.unit); val who=field(item.requestedBy,"Richiesto da"); val urgent=CheckBox(this).apply{text="Urgente";isChecked=item.urgent}; val state=spinner(ToolsStore.REQUEST_STATUSES,item.status); val note=multiline(item.note,"Nota")
        addLabeled(box,"Nome",name);addLabeled(box,"Tipo",kind);addLabeled(box,"Quantità",qty);addLabeled(box,"Unità",unit);addLabeled(box,"Richiesto da",who);box.addView(urgent,lp52());addLabeled(box,"Stato",state);addLabeled(box,"Nota",note,82)
        val d=AlertDialog.Builder(this).setTitle(if(item.id>0)"Modifica richiesta" else "Nuova richiesta").setView(ScrollView(this).apply{addView(box)}).setPositiveButton("Salva",null).setNegativeButton("Annulla",null).create()
        d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{if(name.text.toString().isBlank()){name.error="Nome obbligatorio";return@setOnClickListener};item.name=name.text.toString().trim();item.kind=if(kind.selectedItemPosition==0)ResourceKind.MATERIAL else ResourceKind.TOOL;item.quantity=parseNumber(qty.text.toString()).coerceAtLeast(.01);item.unit=unit.selectedItem.toString();item.requestedBy=who.text.toString().trim();item.urgent=urgent.isChecked;item.status=state.selectedItem.toString();item.note=note.text.toString().trim();store.saveRequest(item);d.dismiss();refresh()}};d.show()
    }

    private fun chooseRequestStatus(item:ResourceRequest)=AlertDialog.Builder(this).setTitle(item.name).setItems(ToolsStore.REQUEST_STATUSES.toTypedArray()){_,p->item.status=ToolsStore.REQUEST_STATUSES[p];store.saveRequest(item);refresh()}.setNeutralButton("Modifica"){_,_->editRequest(item.copy())}.show()

    private fun editTask(item: TeamTask) {
        val job=selectedJob();if(item.jobsiteId.isBlank()){if(job==null)return toast("Scegli prima un cantiere");item.jobsiteId=job.ge360Id;item.jobsiteCode=job.code;item.jobsiteTitle=job.displayTitle()}
        val box=formBox();val title=field(item.title,"Cosa fare");val type=spinner(ToolsStore.TASK_TYPES,item.type);val assigned=field(item.assignedTo,"Assegnato a");val due=field(item.dueDate,"AAAA-MM-GG");val priority=spinner(listOf("Normale","Media","Alta","Urgente"),listOf("Normale","Media","Alta","Urgente")[item.priority.coerceIn(0,3)]);val state=spinner(ToolsStore.TASK_STATUSES,item.status);val description=multiline(item.description,"Dettagli")
        addLabeled(box,"Titolo",title);addLabeled(box,"Tipo",type);addLabeled(box,"Assegnato a",assigned);addLabeled(box,"Data",due);addLabeled(box,"Priorità",priority);addLabeled(box,"Stato",state);addLabeled(box,"Dettagli",description,82)
        val d=AlertDialog.Builder(this).setTitle(if(item.id>0)"Modifica task" else "Nuovo task squadra").setView(ScrollView(this).apply{addView(box)}).setPositiveButton("Salva",null).setNegativeButton("Annulla",null).create()
        d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{if(title.text.toString().isBlank()){title.error="Titolo obbligatorio";return@setOnClickListener};item.title=title.text.toString().trim();item.type=type.selectedItem.toString();item.assignedTo=assigned.text.toString().trim();item.dueDate=due.text.toString().trim();item.priority=priority.selectedItemPosition;item.status=state.selectedItem.toString();item.description=description.text.toString().trim();store.saveTask(item);d.dismiss();refresh()}};d.show()
    }

    private fun chooseTaskStatus(item:TeamTask)=AlertDialog.Builder(this).setTitle(item.title).setItems(ToolsStore.TASK_STATUSES.toTypedArray()){_,p->item.status=ToolsStore.TASK_STATUSES[p];store.saveTask(item);refresh()}.setNeutralButton("Modifica"){_,_->editTask(item.copy())}.show()

    private fun dataMenu() = AlertDialog.Builder(this).setTitle("Dati Attrezzi").setItems(arrayOf("Esporta backup JSON","Importa e unisci","Importa e sostituisci")){_,i->when(i){0->startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="application/json";putExtra(Intent.EXTRA_TITLE,"ge360-attrezzi-backup.json")},REQ_EXPORT);1->{replaceOnImport=false;openImport()};2->{replaceOnImport=true;openImport()}}}.show()
    private fun openImport()=startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="application/json";addCategory(Intent.CATEGORY_OPENABLE)},REQ_IMPORT)
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=Activity.RESULT_OK||data?.data==null)return;val uri=data.data!!;when(requestCode){REQ_EXPORT->runCatching{contentResolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use{it.write(store.exportJson())}?:error("File non scrivibile")}.onSuccess{toast("Backup esportato")}.onFailure{toast(it.message?:"Errore")};REQ_IMPORT->runCatching{val text=contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}?:error("File non leggibile");store.importJson(text,replaceOnImport)}.onSuccess{toast("Importati $it elementi");refresh()}.onFailure{toast("Import fallito: ${it.message}")}}}

    private fun formBox()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(6),dp(18),dp(8))}
    private fun field(value:String,hintText:String)=EditText(this).apply{setText(value);hint=hintText;isSingleLine=true}
    private fun multiline(value:String,hintText:String)=EditText(this).apply{setText(value);hint=hintText;minLines=2;gravity=Gravity.TOP}
    private fun numberField(value:Double)=EditText(this).apply{inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;setText(value.clean().replace('.',','));isSingleLine=true}
    private fun spinner(values:List<String>,selected:String)=Spinner(this).apply{adapter=ArrayAdapter(this@ToolsActivity,android.R.layout.simple_spinner_dropdown_item,values);setSelection(values.indexOf(selected).coerceAtLeast(0))}
    private fun addLabeled(box:LinearLayout,label:String,view:View,height:Int=52){box.addView(TextView(this).apply{text=label;setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(7),0,0)});box.addView(view,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(height)))}
    private fun match(q:String,vararg values:String)=q.isBlank()||values.any{it.lowercase(Locale.ITALY).contains(q)}
    private fun parseNumber(raw:String)=raw.trim().replace(" ","").replace(',','.').filter{it.isDigit()||it=='.'||it=='-'}.toDoubleOrNull()?:0.0
    private fun kindLabel(k:ResourceKind)=if(k==ResourceKind.TOOL)"Attrezzo" else "Materiale"
    private fun priorityIcon(p:Int)=when(p){3->"🔴 ";2->"🟠 ";1->"🟡 ";else->""}
    private fun Double.clean()=if(this%1.0==0.0)toInt().toString() else String.format(Locale.ITALY,"%.2f",this)
    private fun toast(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    private fun lp52()=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52))
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()

    private data class Row(val type:String,val id:Long,val title:String,val subtitle:String,val payload:Any)
    private inner class RowAdapter:BaseAdapter(){var rows:List<Row> = emptyList();override fun getCount()=rows.size;override fun getItem(position:Int)=rows[position];override fun getItemId(position:Int)=rows[position].id;override fun getView(position:Int,convertView:View?,parent:ViewGroup?):View{val r=rows[position];return MaterialCardView(this@ToolsActivity).apply{radius=dp(14).toFloat();cardElevation=dp(1).toFloat();layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{setMargins(0,dp(4),0,dp(4))};addView(LinearLayout(this@ToolsActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(11),dp(14),dp(11));addView(TextView(this@ToolsActivity).apply{text=r.title;textSize=16f;setTypeface(typeface,Typeface.BOLD)});addView(TextView(this@ToolsActivity).apply{text=r.subtitle;textSize=12f;setPadding(0,dp(4),0,0)})})}}}
    private class Selected(private val block:()->Unit):AdapterView.OnItemSelectedListener{override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long)=block();override fun onNothingSelected(parent:AdapterView<*>?)=Unit}

    companion object { private const val MODE_PREP="prep";private const val MODE_CATALOG="catalog";private const val MODE_REQUESTS="requests";private const val MODE_TASKS="tasks";private const val REQ_EXPORT=701;private const val REQ_IMPORT=702 }
}

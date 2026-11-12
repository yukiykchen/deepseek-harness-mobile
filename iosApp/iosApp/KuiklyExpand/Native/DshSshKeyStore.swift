import Foundation
import Security
import UniformTypeIdentifiers
import UIKit

@objc(DshSshKeyStore)
final class DshSshKeyStore: NSObject, UIDocumentPickerDelegate {
    @objc static let shared = DshSshKeyStore()

    private let records = DshSshKeyRecords()
    private var pickerCompletion: ((String) -> Void)?
    private var picker: UIDocumentPickerViewController?

    override init() {
        super.init()
        do { try removeLegacySshPickerFiles(in: FileManager.default.temporaryDirectory) }
        catch { NSLog("[DshSshKeyStore] Cannot remove legacy picker files") }
    }

    @objc func pickKey(from presenter: UIViewController, completion: @escaping (String) -> Void) {
        guard picker == nil else { completion(""); return }
        records.cancelSelection()
        pickerCompletion = completion
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.item, .data], asCopy: true)
        controller.delegate = self
        controller.allowsMultipleSelection = false
        picker = controller
        presenter.present(controller, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard controller === picker else { return }
        defer {
            picker = nil
            pickerCompletion = nil
        }
        guard let url = urls.first else {
            pickerCompletion?("")
            return
        }
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try readSshKeyFile(url)
            pickerCompletion?(try records.stage(data))
        } catch {
            pickerCompletion?("")
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        guard controller === picker else { return }
        records.cancelSelection()
        pickerCompletion?("")
        picker = nil
        pickerCompletion = nil
    }

    @objc func importBytes(_ bytes: Data, name: String) -> String {
        (try? records.importBytes(bytes)) ?? ""
    }

    @objc func importUri(_ uri: String) -> String? {
        try? records.consume(uri)
    }

    @objc func exists(_ keyId: String) -> Bool {
        (try? records.read(keyId)) != nil
    }

    @objc func readKeyBytes(_ keyId: String) -> Data? {
        try? records.read(keyId)
    }

    @objc func deleteKey(_ keyId: String) {
        do { try records.delete(keyId) }
        catch { NSLog("[DshSshKeyStore] Cannot delete SSH key") }
    }

    @objc(validateKey:passphrase:)
    func validateKey(_ keyId: String, passphrase: String) -> Bool {
        guard let data = try? records.read(keyId) else { return false }
        return DshSshKeyValidator.validateKeyData(data, passphrase: passphrase)
    }

}
